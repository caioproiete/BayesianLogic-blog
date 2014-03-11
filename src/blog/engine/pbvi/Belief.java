package blog.engine.pbvi;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import blog.common.Util;
import blog.engine.onlinePF.ObservabilitySignature;
import blog.engine.onlinePF.PFEngine.PFEngineSampled;
import blog.engine.onlinePF.inverseBucket.TimedParticle;
import blog.engine.onlinePF.inverseBucket.UBT;
import blog.model.Evidence;
import blog.model.Function;
import blog.model.Type;
import blog.world.AbstractPartialWorld;

public class Belief {
	private PFEngineSampled pf;
	private OUPBVI pbvi;
	private Map<State, Integer> stateCounts;
	private Evidence latestEvidence;
	
	public Belief(OUPBVI pbvi) {
		this.stateCounts = new HashMap<State, Integer>();
		this.pbvi = pbvi;
	}
	
	public Belief(Map<State, Integer> states, OUPBVI pbvi) {
		this.stateCounts = new HashMap<State, Integer>();
		for (State s : states.keySet()) {
			this.stateCounts.put(s, states.get(s)); 
		}
		this.pbvi = pbvi;
		zeroTimestep();
	}
	
	public Belief(PFEngineSampled pf, OUPBVI pbvi) {
		this.pf = pf;
		this.pbvi = pbvi;
		
		stateCounts = new HashMap<State, Integer>();
		for (TimedParticle tp : pf.particles) {
			State s = new State((AbstractPartialWorld) tp.curWorld, tp.getTimestep());
			addState(s);
		}
	}
	
	public int getTimestep() {
		return ((State) Util.getFirst(getStates())).getTimestep();
	}
	
	public void addState(State s) {
		addState(s, 1);
	}
	
	public void addState(State s, Integer count) {
		if (!stateCounts.containsKey(s)) {
			stateCounts.put(s, 0);
		}
		stateCounts.put(s, stateCounts.get(s) + count);
	}
	
	public void addBelief(Belief b) {
		for (State s : b.getStates()) {
			addState(s, b.getCount(s));
		}
	}
	
	public Belief takeAction(Evidence action) {
		PFEngineSampled actionPropagated = pf.copy();
		actionPropagated.beforeTakingEvidence();
		actionPropagated.takeDecision(action);
		for (TimedParticle p : actionPropagated.particles)
			p.advanceTimestep();
		actionPropagated.answer(pbvi.getQueries(getTimestep() + 1));
		return new Belief(actionPropagated, pbvi);
	}
	
/*	public Belief takeObservation(Evidence obs) {
		PFEngineSampled next = pf.copy();
		next.beforeTakingEvidence();
		next.takeDecision(obs);
		next.answer(pbvi.getQueries(getTimestep()));
		try {
			next.resample();
		} catch (IllegalArgumentException e) {
			return null;
		}
		return new Belief(next, pbvi);
	}*/
	static long copyTime = 0;
	static long takeActionTime = 0;
	
	static long takeObsTime = 0;
	static long resampleTime = 0;
	static int numSampleNext = 0;
	static Map<Integer, Integer> resampleStateCountStats = new HashMap<Integer, Integer>();
	static Map<Integer, Integer> stateCountStats = new HashMap<Integer, Integer>();
	public Belief sampleNextBelief(Evidence action) {
		numSampleNext++;
		
		Timer.start();
		PFEngineSampled nextPF = getParticleFilter().copy();
		copyTime += Timer.getElapsed();
		
		Timer.start();
		nextPF.beforeTakingEvidence();
		nextPF.takeDecision(action);
		nextPF.answer(pbvi.getQueries(getTimestep() + 1));
		takeActionTime += Timer.getElapsed();
		
		for (TimedParticle p : nextPF.particles)
			p.advanceTimestep();

		Timer.start("updateOS");
		nextPF.updateOSforAllParticles();
		Timer.record("updateOS");
		
		int osIndex = nextPF.particles.get(0).getOS();
		//if (nextPF.particles.size() > 1) {
			Timer.start("retakeObs2");
			nextPF.retakeObservability2();
			Timer.record("retakeObs2");

			Timer.start("retakeObs");
			osIndex = nextPF.retakeObservability();	
			Timer.record("retakeObs");
		//} 
		
		Evidence o = ObservabilitySignature.getOSbyIndex(osIndex).getEvidence();
		
		Timer.start("dropHistory");
		if (UBT.dropHistory) {
			nextPF.dropHistory();
			//ObservabilitySignature.dropHistory(((TimedParticle)Util.getFirst(nextPF.particles)).getTimestep());
		}
		Timer.record("dropHistory");
		//takeObsTime += Timer.getElapsed();
		
		Timer.start();
		nextPF.resample();
		resampleTime += Timer.getElapsed();
		
		Belief nextBelief = new Belief(nextPF, pbvi);
		nextBelief.latestEvidence = o;
		
		updateResampleStateCountStats(nextBelief);
		updateStateCountStats(this);
		return nextBelief;
	}
	
	public double getReward(Evidence action) {
		double total = 0;
		
		PFEngineSampled apPF = getParticleFilter().copy();
		apPF.beforeTakingEvidence();
		apPF.takeDecision(action);
		apPF.answer(pbvi.getQueries(getTimestep() + 1));
		
		Function rewardFunc = (Function) pbvi.getModel().getRandomFunc("reward", 1);
		Object timestep = Type.getType("Timestep").getGuaranteedObject(getTimestep());
		
		for (TimedParticle p : apPF.particles) {
			Number reward = (Number) rewardFunc.getValueSingleArg(timestep, p.getLatestWorld());
			total += reward.doubleValue();
		}
		System.out.println(total/apPF.particles.size());
		return total/apPF.particles.size();
	}
	
	public ActionPropagated beliefsAfterAction(Evidence action) {
		updateStateCountStats(this);
		ActionPropagated ap = new ActionPropagated(this, action);
		
		PFEngineSampled apPF = getParticleFilter().copy();
		apPF.beforeTakingEvidence();
		apPF.takeDecision(action);
		apPF.answer(pbvi.getQueries(getTimestep() + 1));
		
		Function rewardFunc = (Function) pbvi.getModel().getRandomFunc("reward", 1);
		Object timestep = Type.getType("Timestep").getGuaranteedObject(getTimestep());
	
		Number reward = (Number) rewardFunc.getValueSingleArg(timestep, 
				apPF.particles.get(0).getLatestWorld());
		ap.setReward(reward.doubleValue());
		
		for (TimedParticle p : apPF.particles)
			p.advanceTimestep();
		apPF.updateOSforAllParticles();
		
		Map<Integer, Double> osWeights = new HashMap<Integer, Double>();
		for (TimedParticle p : apPF.particles) {
			Integer os = p.getOS();
			if (!osWeights.containsKey(os))
				osWeights.put(os, 0.0);
			osWeights.put(os, osWeights.get(os) + p.getLatestWeight());
		}
		//System.out.println("Num observations " + osWeights.size());
		ap.setActionPropagatedPF(apPF);
		for (Integer osIndex : osWeights.keySet()) {
			PFEngineSampled nextPF = apPF.copy();
			nextPF.retakeObservability2(osIndex);
			nextPF.retakeObservability(osIndex);	
			
			//if (UBT.dropHistory) {
				//nextPF.dropHistory();
				//ObservabilitySignature.dropHistory(((TimedParticle)Util.getFirst(nextPF.particles)).getTimestep());
			//}
			//nextPF.resample();
			/*Belief nextBelief = new Belief(nextPF, pbvi);
			result.put(o, nextBelief);
			ap.setNextBelief(o, nextBelief);*/
			Evidence o = ObservabilitySignature.getOSbyIndex(osIndex).getEvidence();
			ap.setObservationWeight(o, osWeights.get(osIndex));
			ap.setOSIndex(o, osIndex);
		}
		return ap;
	}
	
	public PFEngineSampled getParticleFilter() {
		if (pf != null)
			return pf;
		
		int count = 0;
		for (State s : getStates()) {
			count += stateCounts.get(s);
		}
		
		Properties properties = (Properties) pbvi.getProperties().clone();
		properties.setProperty("numParticles", "" + count);
		pf = new PFEngineSampled(pbvi.getModel(), properties);
		List<TimedParticle> particles = pf.particles;
		int j = 0;
		for (State s : getStates()) {
			for (int i = 0; i < stateCounts.get(s); i++) {
				particles.get(j).setWorld(s.getWorld());
				particles.get(j).setTimestep(s.getTimestep());
				j++;
			}
		}
		return pf;
	}
	
	public Set<State> getStates() {
		return stateCounts.keySet();
	}
	
	public Integer getCount(State s) {
		Integer count = stateCounts.get(s);
		if (count == null) 
			return 0;
		return count;
	}
	
	public String toString() {
		Set<State> states = getStates();
		String result = "";
		for (State s : states) {
			result += getCount(s) + " " + s.getWorld() + "\n";
		}
		return result;
	}

	public void setParticleFilter(PFEngineSampled particleFilter) {
		this.pf = particleFilter;
	}

	public void setPBVI(OUPBVI oupbvi) {
		this.pbvi = oupbvi;
	}
	
	public Evidence getLatestEvidence() {
		return latestEvidence;
	}
	
	public int diffNorm(Belief other) {
		Set<State> unionStates = new HashSet<State>(this.stateCounts.keySet());
		unionStates.addAll(other.getStates());
		int diff = 0;
		for (State s : unionStates) {
			diff += Math.abs(this.getCount(s) - other.getCount(s));
		}
		return diff;
	}
	
	public void zeroTimestep() {
		Set<State> states = getStates();
		Map<State, Integer> newStateCounts = new HashMap<State, Integer>();
		for (State s : states) {
			int count = stateCounts.get(s);
			s.zeroTimestep();
			newStateCounts.put(s, count);
		}
		stateCounts = newStateCounts;
		pf = null;
	}
	
	public static void printTimingStats() {
		System.out.println("Belief.resampleTime " + resampleTime);
		System.out.println("Belief.copyTime " + copyTime);
		System.out.println("Belief.takeActionTime " + takeActionTime);
		System.out.println("Belief.takeObsTime " + takeObsTime);
		System.out.println("Belief.numSampleNext " + numSampleNext);
		
		System.out.println("State counts " + stateCountStats);
		System.out.println("Resample state counts " + resampleStateCountStats);
		Timer.print();
	}

	public static void updateResampleStateCountStats(Belief nextBelief) {
		int numStatesAfterResample = nextBelief.getStates().size();
		if (!resampleStateCountStats.containsKey(numStatesAfterResample)) {
			resampleStateCountStats.put(numStatesAfterResample, 0);
		}
		resampleStateCountStats.put(numStatesAfterResample, resampleStateCountStats.get(numStatesAfterResample) + 1);
	}
	
	public static void updateStateCountStats(Belief belief) {
		int numStates = belief.getStates().size();
		if (!stateCountStats.containsKey(numStates)) {
			stateCountStats.put(numStates, 0);
		}
		stateCountStats.put(numStates, stateCountStats.get(numStates) + 1);
	}
}