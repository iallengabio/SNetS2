package com.snets2.model;

import com.snets2.SimulationConstants;
import java.util.BitSet;

/**
 * Represents the frequency spectrum of an optical core, partitioned into discrete frequency slots.
 * 
 * <p>This class manages the occupancy state of the spectrum using a {@link BitSet} for high-performance
 * operations. In Elastic Optical Networks (EON), spectrum is allocated in contiguous blocks of slots,
 * following the continuity and contiguity constraints.</p>
 */
public class Spectrum {
    private final int numSlots;
    private final BitSet slots; // true = occupied, false = free

    /**
     * Constructs a new Spectrum instance with the specified number of slots.
     *
     * @param numSlots Total number of frequency slots in this core's grid.
     */
    public Spectrum(int numSlots) {
        this.numSlots = numSlots;
        this.slots = new BitSet(numSlots);
    }

    /**
     * Returns the total number of slots in this spectrum.
     *
     * @return Total slot count.
     */
    public int getNumSlots() {
        return numSlots;
    }

    /**
     * Checks if a specific slot is free.
     *
     * @param slotIndex Index of the slot to check.
     * @return true if the slot is free, false otherwise.
     */
    public boolean isFree(int slotIndex) {
        return !slots.get(slotIndex);
    }

    /**
     * Checks if a specific slot is occupied.
     *
     * @param slotIndex Index of the slot to check.
     * @return true if the slot is occupied, false otherwise.
     */
    public boolean isOccupied(int slotIndex) {
        return slots.get(slotIndex);
    }

    /**
     * Allocates a contiguous range of slots.
     *
     * @param startSlot The starting index of the allocation (inclusive).
     * @param endSlot   The ending index of the allocation (inclusive).
     * @throws IllegalArgumentException if the range is invalid or out of bounds.
     * @throws IllegalStateException if any slot in the range is already occupied and strict validation is enabled.
     */
    public void allocate(int startSlot, int endSlot) {
        if (startSlot < 0 || endSlot >= numSlots || startSlot > endSlot) {
            throw new IllegalArgumentException("Invalid slot range: [" + startSlot + ", " + endSlot + "]");
        }
        
        if (SimulationConstants.strictValidationEnabled) {
            if (!isRangeFree(startSlot, endSlot)) {
                throw new IllegalStateException("Attempted to allocate already occupied spectrum range: [" + startSlot + ", " + endSlot + "]");
            }
        }
        
        slots.set(startSlot, endSlot + 1);
    }

    /**
     * Releases a previously allocated range of slots.
     *
     * @param startSlot The starting index of the range (inclusive).
     * @param endSlot   The ending index of the range (inclusive).
     * @throws IllegalArgumentException if the range is invalid or out of bounds.
     * @throws IllegalStateException if any slot in the range is already free and strict validation is enabled.
     */
    public void release(int startSlot, int endSlot) {
         if (startSlot < 0 || endSlot >= numSlots || startSlot > endSlot) {
            throw new IllegalArgumentException("Invalid slot range: [" + startSlot + ", " + endSlot + "]");
        }

        if (SimulationConstants.strictValidationEnabled) {
            // All slots in the range MUST be occupied before releasing
            for (int i = startSlot; i <= endSlot; i++) {
                if (!slots.get(i)) {
                    throw new IllegalStateException("Attempted to release free slot at index " + i + " in range [" + startSlot + ", " + endSlot + "]");
                }
            }
        }

        slots.clear(startSlot, endSlot + 1);
    }

    /**
     * Checks if a contiguous range of slots is entirely free.
     *
     * @param startSlot The starting index of the range (inclusive).
     * @param endSlot   The ending index of the range (inclusive).
     * @return true if all slots in the range are free, false otherwise.
     */
    public boolean isRangeFree(int startSlot, int endSlot) {
         if (startSlot < 0 || endSlot >= numSlots || startSlot > endSlot) {
            return false;
        }
        int nextSet = slots.nextSetBit(startSlot);
        return nextSet == -1 || nextSet > endSlot;
    }
}
