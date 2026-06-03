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
