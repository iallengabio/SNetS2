package com.snets2.model;

import java.util.List;

/**
 * Represents a single spatial core within a Multicore Fiber (MC-EON).
 * 
 * <p>Each core maintains its own {@link Spectrum} grid and knows its spatial neighbors
 * (adjacent cores). This adjacency information is critical for calculating inter-core 
 * Crosstalk (XT), which is the primary physical impairment in SDM-based networks.</p>
 */
public class Core {
    private final int id;
    private final List<Integer> adjacentCores;
    private final Spectrum spectrum;
    private final double[] nliNoiseCache;
    private final double[] xtNoiseCache;

    /**
     * Constructs a Core with its spatial and spectral properties.
     *
     * @param id            Unique identifier for the core within the link.
     * @param adjacentCores List of IDs of cores that are physically adjacent to this one.
     * @param numSlots      Number of frequency slots available in this core's spectrum.
     */
    public Core(int id, List<Integer> adjacentCores, int numSlots) {
        this.id = id;
        this.adjacentCores = List.copyOf(adjacentCores);
        this.spectrum = new Spectrum(numSlots);
        this.nliNoiseCache = new double[numSlots];
        this.xtNoiseCache = new double[numSlots];
    }

    /**
     * Adds NLI noise contribution to a specific slot.
     */
    public void addNliNoise(int slot, double noise) {
        nliNoiseCache[slot] += noise;
    }

    /**
     * Removes NLI noise contribution from a specific slot.
     */
    public void removeNliNoise(int slot, double noise) {
        nliNoiseCache[slot] -= noise;
        if (nliNoiseCache[slot] < 0) nliNoiseCache[slot] = 0;
    }

    /**
     * Adds Crosstalk noise contribution to a specific slot.
     */
    public void addXtNoise(int slot, double noise) {
        xtNoiseCache[slot] += noise;
    }

    /**
     * Removes Crosstalk noise contribution from a specific slot.
     */
    public void removeXtNoise(int slot, double noise) {
        xtNoiseCache[slot] -= noise;
        if (xtNoiseCache[slot] < 0) xtNoiseCache[slot] = 0;
    }

    /**
     * Gets the average NLI noise density in a range of slots.
     */
    public double getAverageNliNoise(int startSlot, int endSlot) {
        double sum = 0;
        for (int i = startSlot; i <= endSlot; i++) {
            sum += nliNoiseCache[i];
        }
        return sum / (endSlot - startSlot + 1);
    }

    /**
     * Gets the average Crosstalk noise density in a range of slots.
     */
    public double getAverageXtNoise(int startSlot, int endSlot) {
        double sum = 0;
        for (int i = startSlot; i <= endSlot; i++) {
            sum += xtNoiseCache[i];
        }
        return sum / (endSlot - startSlot + 1);
    }

    /**
     * Gets the unique ID of the core.
     *
     * @return Core identifier.
     */
    public int getId() {
        return id;
    }

    /**
     * Returns the IDs of cores adjacent to this one.
     * Used for Crosstalk (XT) calculations.
     *
     * @return Immutable list of adjacent core IDs.
     */
    public List<Integer> getAdjacentCores() {
        return adjacentCores;
    }

    /**
     * Returns the frequency spectrum associated with this core.
     *
     * @return The {@link Spectrum} instance.
     */
    public Spectrum getSpectrum() {
        return spectrum;
    }
}
