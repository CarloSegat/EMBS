package lsi.instruction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ptolemy.actor.TypedAtomicActor;
import ptolemy.actor.TypedIOPort;
import ptolemy.actor.parameters.IntRangeParameter;
import ptolemy.data.IntToken;
import ptolemy.data.RecordToken;
import ptolemy.data.expr.Parameter;
import ptolemy.data.expr.StringParameter;
import ptolemy.data.type.BaseType;
import ptolemy.kernel.CompositeEntity;
import ptolemy.kernel.util.IllegalActionException;
import ptolemy.kernel.util.NameDuplicationException;

/**
 *  Represents a local memory attached to a processing element (PE).
 */
public class Cache extends TypedAtomicActor {
	
	// Write-hit policies
	public static final int WRITE_BACK = 0;     // Write to cache now, write to main later if cache word is overwritten
	public static final int WRITE_THROUGH = 1;  // Write to cache and main at the same time
	
	// Write-miss policies
	public static final int WRITE_AROUND = 2;   // Just write to main
	public static final int WRITE_ALLOCATE = 3; // Load into cache & write on cache and main

	// Cache parameters that can be set in the Ptolemy simulation
	Parameter size;
	Parameter usingWritePolicies;
	Parameter writeHitPolicy;
	Parameter writeMissPolicy;
	
	
	// The data structure containing the stored addresses
	PhysicalMemory memory;
	
	// Statistics for cache-hits, cache-misses and number of write operations to main memory
	Map<String, Integer> cacheStats;
	
	// Input port that receives the addresses used by PE to drive the bus
	TypedIOPort instructionsInput;	
	
	// The instruction the PE has sent last. While the PE has not got a grant signal from the bus, the PE keeps driving the bus with the same data. 
	// By knowing the previously seen instruction we can determine when a bus transaction has happened and when the PE is just waiting.
	Instruction previousInstruction = null;
	
	public Cache(CompositeEntity container, String name) throws IllegalActionException, NameDuplicationException {
		super(container, name);
		setupInputPort();
		setupParameters();
	}

	

	public void initialize() throws IllegalActionException{
		super.initialize();
		memory = new PhysicalMemory(Integer.parseInt(size.getValueAsString()));
		
		initializeCacheStats();
	}

	/*
	 * When this method is called it means that the connected PE has driven the bus with a request that we represent as an instruction.
	 * @see ptolemy.actor.AtomicActor#fire()
	 */
	public void fire() throws IllegalActionException {
		if(instructionsInput.hasToken(0)) {
	
			RecordToken t = (RecordToken)instructionsInput.get(0);
			Instruction instruction = makeInstructionFromToken(t);
			if(areEqual(previousInstruction, instruction)) {
				return;
			}
			previousInstruction = instruction;
			simulateInstruction(instruction);
		} 	
	}

	public void simulateInstruction(Instruction instruction) throws IllegalActionException {
		
		if(Boolean.parseBoolean(usingWritePolicies.getValueAsString())) {
			// using writing policies affects the effects of write instructuions
			if(instruction.type == 2) { 
				// Current instruction is data to be written at a certain address
	 			simulateWrite(instruction);
			} else {
				// regardless of wheter it is a read, jump, execute instruction or data to read we need to fectch a memory location from memory
				simulateFetch(instruction);
			}
		} else {
			// When not using write policies we simply write to cache the data that the write instruction writes
			// Instructions that read that data may generate a cache hit because of that
			if(instruction.type == 2) { 
				memory.load(instruction.address, false);
			} else {
				if(memory.hasAddressLoaded(instruction.address)) {
					increment("read-hit");
				} else {
					increment("read-miss");
					memory.load(instruction.address, false);
				}
			}
		}
	}
	
	private void simulateFetch(Instruction i) throws IllegalActionException {
		if(memory.hasAddressLoaded(i.address)) {
			increment("read-hit");
		} else {
			increment("read-miss");
			simulateLoadWithWritePolicies(i, false);
		}
	}
	
	private void simulateWrite(Instruction i) throws IllegalActionException {
		if(memory.hasAddressLoaded(i.address)) {
			simulateWriteHit();
		} else {
			simulateWriteMiss(i);
		}
	}

	private void simulateWriteMiss(Instruction i) throws IllegalActionException {
		increment("write-miss");
		if(Integer.parseInt(writeMissPolicy.getValueAsString()) == WRITE_AROUND) {
			increment("write-to-main");
			// Write Around do not load to cache
		} else { 
			// Write Allocate both load to cache and update main
			simulateLoadWithWritePolicies(i, true);
			increment("write-to-main"); // Write allocate write both to cache and main
		}
	}

	/**
	 * Simulated the effects of either loading data or an instruction which are NOT already in the cache.
	 * @param instruction The instruction or data we need to fetch or load.
	 * @param isData True if loading data, false otherwise.
	 * @throws IllegalActionException 
	 */
	private void simulateLoadWithWritePolicies(Instruction instruction, boolean isData) throws IllegalActionException {
		if(memory.load(instruction.address, isData)) {
			// Cached line flagged as data was overwritten, if we use write back now it is time to persist to main memory the cached data about to be overwritten 
			if(Integer.parseInt(writeHitPolicy.getValueAsString()) == WRITE_BACK) { 
				// Write back transfers cache content before it is overwritten
				increment("write-to-main");
			}
		};
		increment("read-from-main");
	}
	

	private void simulateWriteHit() throws IllegalActionException {
		increment("write-hit");
		if(Integer.parseInt(writeHitPolicy.getValueAsString()) == WRITE_THROUGH) {
			// Write-through always writes both to cache and main memory to ensure consistency
			increment("write-to-main");
		}
		// If using write back we just update cache so we dont need to do anything
	}
	
	/**
	 * Increment a cache statistic.
	 * @param cacheStatistic String representing the statistic to increment. 
	 * Values are: read-hit, read-miss, write-miss, write-hit, write-to-main, read-from-main
	 */
	private void increment(String cacheStatistic) {
		cacheStats.put(cacheStatistic, cacheStats.get(cacheStatistic) + 1);
	}

	private void initializeCacheStats() {
		cacheStats = new HashMap<String, Integer>();
		cacheStats.put("read-hit", 0); // Fetches or Read instructions that need to access main memory
		cacheStats.put("read-miss", 0);  // Fetches or read instructions that DONT need to access main memory
		cacheStats.put("write-hit", 0);  // Write instructions that finds address in the cache
		cacheStats.put("write-miss", 0);  // Write instructions that DONT finds address in the cache
		cacheStats.put("write-to-main", 0); // number of times the PE had to write to main memory
		cacheStats.put("read-from-main", 0); // number of times the PE had to from main memory
		// Generally, read-from-main will be equal to read-miss. But when we use a write-allocate policy
		// we need to read from main memory when the address we want to write to is not in the cache.
	}
	
	/** 
	 * Transforms the record token we get from the input of this actor into an instance of the instruction class.
	 * Effectively the data used by a PE to drive the bus can be interpreted as an instruction.
	 * @param recordToken
	 * @return
	 */
	private Instruction makeInstructionFromToken(RecordToken recordToken) {
		Instruction i = new Instruction(
				((IntToken)recordToken.get("type")).intValue(),
				((IntToken)recordToken.get("data")).intValue(),
				((IntToken)recordToken.get("address")).intValue(), 
				((IntToken)recordToken.get("time")).intValue() 
		);
		return i;
	}
	
	/**
	 * Returns true if the two instructions are the same.
	 * @param right An instruction
	 * @param left n instruction
	 * @return
	 */
	private boolean areEqual(Instruction right, Instruction left) {
		assert(left != null);
		if (right != null && 
			right.type == left.type &&
			right.address == left.address &&
			right.data == left.data &&
			right.time == left.time) {
			return true;
		}
		return false;
	}
	
	private void setupParameters() throws IllegalActionException, NameDuplicationException {
		// Parameters initialisation
		size = new Parameter(this, "size");
		writeHitPolicy = new Parameter(this, "writeHitPolicy");
		writeMissPolicy = new Parameter(this, "writeMissPolicy");
		usingWritePolicies = new Parameter(this, "usingWritePolicies");
		// Parameters type
		size.setTypeEquals(BaseType.INT);
		writeHitPolicy.setTypeEquals(BaseType.INT);
		writeMissPolicy.setTypeEquals(BaseType.INT);
		usingWritePolicies.setTypeEquals(BaseType.BOOLEAN);
		// Starting values
		size.setExpression("128");
		writeHitPolicy.setExpression("0");
		writeMissPolicy.setExpression("2");
		writeMissPolicy.setExpression("false");
	}
	
	private void setupInputPort() throws IllegalActionException, NameDuplicationException {
		instructionsInput = new TypedIOPort(this, "input", true, false);
		instructionsInput.setTypeEquals(Instruction.getTokenType());
	}
	
	public void wrapup() throws IllegalActionException {
		System.out.println(cacheStats);
	}

}


