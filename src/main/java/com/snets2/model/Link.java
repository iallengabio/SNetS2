package com.snets2.model;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Represents a physical optical link (fiber bundle) connecting two ROADM nodes.
 * 
 * <p>A Link is characterized by its physical length, which impacts attenuation and dispersion,
 * and its spatial structure (multiple cores). It also contains a sequence of {@link Amplifier}s
 * that compensate for fiber losses but introduce ASE noise.</p>
 */
public class Link {
    private final String sourceId;
    private final String destinationId;
    private final double length; // in km
    private final Map<Integer, Core> cores;
    private final List<Amplifier> amplifiers;

    /**
     * Constructs a Link with its cores and amplifiers.
     *
     * @param sourceId      Identifier of the source node.
     * @param destinationId Identifier of the destination node.
     * @param length        Total physical length of the fiber in kilometers.
     * @param coresList     List of spatial cores available in this fiber.
     * @param amplifiers    List of amplifiers deployed along the link.
     */
    public Link(String sourceId, String destinationId, double length, List<Core> coresList, List<Amplifier> amplifiers) {
        this.sourceId = sourceId;
        this.destinationId = destinationId;
        this.length = length;
        this.cores = coresList.stream().collect(Collectors.toMap(Core::getId, Function.identity()));
        this.amplifiers = List.copyOf(amplifiers);
    }

    /**
     * Gets the source node identifier.
     *
     * @return Source node ID.
     */
    public String getSourceId() {
        return sourceId;
    }

    /**
     * Gets the destination node identifier.
     *
     * @return Destination node ID.
     */
    public String getDestinationId() {
        return destinationId;
    }

    /**
     * Gets the total length of the link.
     *
     * @return Length in km.
     */
    public double getLength() {
        return length;
    }

    /**
     * Retrieves a specific core by its identifier.
     *
     * @param coreId The ID of the core to retrieve.
     * @return The {@link Core} instance, or null if not found.
     */
    public Core getCore(int coreId) {
        return cores.get(coreId);
    }

    /**
     * Returns all cores in this link.
     *
     * @return A map of core IDs to {@link Core} instances.
     */
    public Map<Integer, Core> getCores() {
        return cores;
    }

    /**
     * Returns the list of amplifiers on this link.
     *
     * @return Immutable list of {@link Amplifier}s.
     */
    public List<Amplifier> getAmplifiers() {
        return amplifiers;
    }
}
