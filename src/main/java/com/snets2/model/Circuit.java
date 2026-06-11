package com.snets2.model;

import java.util.List;

/**
 * Represents an active optical lightpath (Circuit) established in the network.
 * 
 * <p>A Circuit stores its physical path, allocated core/spectrum resources, and the 
 * modulation format used. It also acts as a repository for Quality of Transmission (QoT)
 * metrics (ASE, NLI, XT) calculated at the time of establishment.</p>
 */
public class Circuit {
    private final String id;
    private final Node source;
    private final Node destination;
    private final List<Link> path;
    private final List<Integer> coreIndices; // One core ID per link in the path
    private final int startSlot;
    private final int endSlot;
    private final ModulationFormat modulation;
    private final double bitRate;
    private final List<Node> regeneratorNodes;
    
    // Quality of Transmission (QoT) metrics calculated during setup
    private double aseNoise;
    private double nliNoise;
    private double crosstalk;

    /**
     * Constructs a Circuit with its assigned resources, parameters, and regenerators.
     */
    public Circuit(String id, Node source, Node destination, List<Link> path, 
                   List<Integer> coreIndices, int startSlot, int endSlot, 
                   ModulationFormat modulation, double bitRate, List<Node> regeneratorNodes) {
        if (path.size() != coreIndices.size()) {
            throw new IllegalArgumentException("Path and coreIndices must have the same size");
        }
        this.id = id;
        this.source = source;
        this.destination = destination;
        this.path = List.copyOf(path);
        this.coreIndices = List.copyOf(coreIndices);
        this.startSlot = startSlot;
        this.endSlot = endSlot;
        this.modulation = modulation;
        this.bitRate = bitRate;
        this.regeneratorNodes = regeneratorNodes != null ? List.copyOf(regeneratorNodes) : List.of();
    }

    /**
     * Constructs a Circuit with its assigned resources and parameters, without regenerators.
     */
    public Circuit(String id, Node source, Node destination, List<Link> path, 
                   List<Integer> coreIndices, int startSlot, int endSlot, 
                   ModulationFormat modulation, double bitRate) {
        this(id, source, destination, path, coreIndices, startSlot, endSlot, modulation, bitRate, List.of());
    }

    public String getId() { return id; }
    public Node getSource() { return source; }
    public Node getDestination() { return destination; }
    public List<Link> getPath() { return path; }
    public List<Integer> getCoreIndices() { return coreIndices; }
    public int getStartSlot() { return startSlot; }
    public int getEndSlot() { return endSlot; }
    public ModulationFormat getModulation() { return modulation; }
    public double getBitRate() { return bitRate; }
    public List<Node> getRegeneratorNodes() { return regeneratorNodes; }

    /** Returns the calculated Amplified Spontaneous Emission (ASE) noise. */
    public double getAseNoise() { return aseNoise; }
    public void setAseNoise(double aseNoise) { this.aseNoise = aseNoise; }

    /** Returns the calculated Non-Linear Interference (NLI) noise. */
    public double getNliNoise() { return nliNoise; }
    public void setNliNoise(double nliNoise) { this.nliNoise = nliNoise; }

    /** Returns the calculated inter-core Crosstalk (XT). */
    public double getCrosstalk() { return crosstalk; }
    public void setCrosstalk(double crosstalk) { this.crosstalk = crosstalk; }
}
