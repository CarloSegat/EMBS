package lsi.instruction;

import java.util.ArrayList;
import java.util.List;

public class PhysicalMemory{ 
	
	private int size; 
	private int[] addressesLoaded;
	private boolean[] addressContainsData;
	private int memoryIndex = 0;
	private List<Integer> hitted = new ArrayList<Integer>();

	public PhysicalMemory(int size) {
		this.size = size;
		addressesLoaded = new int[size];
		for(int i = 0; i<size; i++) {
			addressesLoaded[i] = -1;
		}
		addressContainsData = new boolean[size]; // Initialized with false by default
		
	}

	public boolean hasAddressLoaded(int address) {
		if(address == 0) {
			System.out.println("igugik");
		}
		for(int loadedAddress: addressesLoaded) {
			if(address == loadedAddress) {
				hitted.add(address);
				return true;
			}
		}
		return false;
	}
	/**
	 * Loads the memory address into memory.
	 * Returns true if cached data had to be overwritten, false otherwise.
	 * @param address The address to load
	 * @param isData True if loading data
	 * @return
	 */
	public boolean load(int address, boolean isData) {
		boolean dataWasOverwritten = false;
		if(addressContainsData[memoryIndex]) {
			dataWasOverwritten  = true;
		}
		if(isData) {
			addressContainsData[memoryIndex] = true;
		}
		addressesLoaded[memoryIndex] = address;
		updateIndex();
		return dataWasOverwritten;
	}

	private void updateIndex() {
		memoryIndex += 1;
		memoryIndex %= this.size;
	}

}
