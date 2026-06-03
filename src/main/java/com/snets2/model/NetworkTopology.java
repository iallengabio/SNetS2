package com.snets2.model;

import java.util.List;

/**
 * Represents the physical mesh of the network (Topology).
 * 
 * <p>This class stores the structural elements of the network (nodes and links)
 * but does not manage the logic or state transitions, which are handled by the 
 * {@link ControlPlane}.</p>
 */
public record NetworkTopology(
    List<Node> nodes,
    List<Link> links,
    List<ModulationFormat> modulations
) {}
