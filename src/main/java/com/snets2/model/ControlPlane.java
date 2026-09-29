package com.snets2.model;

import com.snets2.SimulationConstants;
import com.snets2.rmsca.IRMSCA;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Control Plane serves as the Single Source of Truth (SSoT) for the network's state.
 */
public class ControlPlane {
    private final NetworkTopology topology;
    private final Map<String, Node> nodesMap;
    private final Map<String, Circuit> activeCircuits;
    private final IRMSCA rmsca;
    private final double slotBandwidth;
    private final int guardBand;
    private final com.snets2.config.PhysicalLayerConfig physicalLayerConfig;

    /**
     * Initializes the Control Plane.
     *
     * @param topology      The physical network topology.
     * @param rmsca         The RMSCA algorithm.
     * @param slotBandwidth The bandwidth of a single spectrum slot in Hz.
     * @param guardBand     The number of guard band slots.
     * @param physConfig    The physical layer configuration parameters.
     */
    public ControlPlane(NetworkTopology topology, IRMSCA rmsca, double slotBandwidth, int guardBand, 
                        com.snets2.config.PhysicalLayerConfig physConfig) {
        this.topology = topology;
        this.nodesMap = new HashMap<>();
        for (Node n : topology.nodes()) {
            this.nodesMap.put(n.getId(), n);
        }
        this.activeCircuits = new HashMap<>();
        this.rmsca = rmsca;
        this.slotBandwidth = slotBandwidth;
        this.guardBand = guardBand;
        this.physicalLayerConfig = physConfig;
        
        initializeStaticNoise();
    }

    private void initializeStaticNoise() {
        if (physicalLayerConfig == null) return;
        for (Link link : topology.links()) {
            // Static (fixed-gain) ASE; with saturated gain it is recomputed at prediction time from the core load.
            double ase = com.snets2.metrics.PhysicalLayerModel.calculateLinkAse(link, physicalLayerConfig, 0.0);
            link.setStaticAseNoise(ase);
        }
    }

    public NetworkTopology getTopology() { return topology; }
    public IRMSCA getRmsca() { return rmsca; }
    public double getSlotBandwidth() { return slotBandwidth; }
    public int getGuardBand() { return guardBand; }
    public com.snets2.config.PhysicalLayerConfig getPhysicalLayerConfig() { return physicalLayerConfig; }

    public Node getNode(String id) { return nodesMap.get(id); }
    public List<Node> getNodes() { return topology.nodes(); }
    public List<Link> getLinks() { return topology.links(); }

    public List<Circuit> getActiveCircuits() {
        return new ArrayList<>(activeCircuits.values());
    }

    /** Read-only live view of the active circuits (no copy). Must not be iterated while circuits are added or removed. */
    public java.util.Collection<Circuit> getActiveCircuitsView() {
        return java.util.Collections.unmodifiableCollection(activeCircuits.values());
    }

    public void establishCircuit(Circuit circuit) {
        if (activeCircuits.containsKey(circuit.getId())) {
            throw new IllegalArgumentException("Circuit ID already exists: " + circuit.getId());
        }

        // --- VALIDATION LAYER ---
        if (SimulationConstants.strictValidationEnabled) {
            validateCircuit(circuit);
        }

        // --- MUTATION LAYER ---
        // 1. Mark slots as occupied and update physical noise caches
        for (int i = 0; i < circuit.getPath().size(); i++) {
            Link link = circuit.getPath().get(i);
            int coreId = circuit.getCoreIndices().get(i);
            Core core = link.getCore(coreId);
            core.getSpectrum().allocate(circuit.getStartSlot(), circuit.getEndSlot());
        }
        applyPhysicalContribution(circuit, true);

        // 2. Consume Tx/Rx on source/destination
        circuit.getSource().consumeTx();
        circuit.getDestination().consumeRx();
        
        // Consume Regenerators on intermediate nodes
        for (Node regenNode : circuit.getRegeneratorNodes()) {
            regenNode.consumeRegenerators(1);
        }

        // 3. Add to active circuits
        activeCircuits.put(circuit.getId(), circuit);
    }

    private void validateCircuit(Circuit circuit) {
        // 1. Structural integrity
        if (circuit.getPath() == null || circuit.getPath().isEmpty()) {
            throw new IllegalStateException("Attempted to establish circuit " + circuit.getId() + " without a path.");
        }
        if (circuit.getModulation() == null) {
            throw new IllegalStateException("Attempted to establish circuit " + circuit.getId() + " without a modulation format.");
        }
        if (circuit.getCoreIndices().size() != circuit.getPath().size()) {
             throw new IllegalStateException("Core indices count does not match path length for circuit " + circuit.getId());
        }

        // 2. Hardware check
        if (!circuit.getSource().hasAvailableTx()) {
            throw new IllegalStateException("Source node " + circuit.getSource().getId() + " has no Tx available");
        }
        if (!circuit.getDestination().hasAvailableRx()) {
            throw new IllegalStateException("Destination node " + circuit.getDestination().getId() + " has no Rx available");
        }
        for (Node regenNode : circuit.getRegeneratorNodes()) {
            if (!regenNode.hasAvailableRegenerators(1)) {
                throw new IllegalStateException("Intermediate node " + regenNode.getId() + " has no regenerators available");
            }
        }

        // 3. Spectrum Continuity & Core Continuity Check
        for (int i = 0; i < circuit.getPath().size(); i++) {
            Link link = circuit.getPath().get(i);
            int coreId = circuit.getCoreIndices().get(i);
            Core core = link.getCore(coreId);
            
            if (core == null) {
                throw new IllegalArgumentException("Core ID " + coreId + " does not exist in link " + link.getSourceId() + "->" + link.getDestinationId());
            }

            if (!core.getSpectrum().isRangeFree(circuit.getStartSlot(), circuit.getEndSlot())) {
                throw new IllegalStateException("Spectrum overlap detected! Slots [" + 
                    circuit.getStartSlot() + "," + circuit.getEndSlot() + "] are already occupied in link " + 
                    link.getSourceId() + "->" + link.getDestinationId() + " core " + coreId);
            }
        }
    }

    public void teardownCircuit(String circuitId) {
        Circuit circuit = activeCircuits.remove(circuitId);
        if (circuit == null) {
            if (SimulationConstants.strictValidationEnabled) {
                throw new IllegalStateException("Attempted to teardown non-existent circuit: " + circuitId);
            }
            return;
        }

        // 1. Mark slots as free and update physical noise caches
        for (int i = 0; i < circuit.getPath().size(); i++) {
            Link link = circuit.getPath().get(i);
            int coreId = circuit.getCoreIndices().get(i);
            Core core = link.getCore(coreId);
            core.getSpectrum().release(circuit.getStartSlot(), circuit.getEndSlot());
        }
        applyPhysicalContribution(circuit, false);

        // 2. Release Tx/Rx
        circuit.getSource().releaseTx();
        circuit.getDestination().releaseRx();
        
        // Release Regenerators on intermediate nodes
        for (Node regenNode : circuit.getRegeneratorNodes()) {
            regenNode.releaseRegenerators(1);
        }
    }

    /**
     * Adds (or removes) the physical footprint of a circuit on every link of its path: its NLI in the
     * same core, its crosstalk in the adjacent cores and its launch power in the core load (used by the
     * saturated-gain amplifier model).
     *
     * <p>Called on setup/teardown and by RMSCA algorithms that temporarily apply a candidate circuit to
     * check the QoT of the circuits already established (QoTO).</p>
     *
     * @param circuit The circuit.
     * @param add     true to add the contribution, false to remove it.
     */
    public void applyPhysicalContribution(Circuit circuit, boolean add) {
        if (physicalLayerConfig == null) return;

        double launchPower = com.snets2.metrics.PhysicalLayerModel.circuitLaunchPower(
            physicalLayerConfig, circuit);

        for (int i = 0; i < circuit.getPath().size(); i++) {
            Link link = circuit.getPath().get(i);
            Core core = link.getCore(circuit.getCoreIndices().get(i));

            if (add) core.addLaunchPower(launchPower);
            else core.removeLaunchPower(launchPower);

            // NLI: Same core, potentially all slots (with decay)
            double[] nliMask = com.snets2.metrics.PhysicalLayerModel.generateNliMask(
                link, physicalLayerConfig, circuit, core.getSpectrum().getNumSlots());
            for (int s = 0; s < nliMask.length; s++) {
                if (add) core.addNliNoise(s, nliMask[s]);
                else core.removeNliNoise(s, nliMask[s]);
            }

            // XT: Adjacent cores, same slots
            double xtContribution = com.snets2.metrics.PhysicalLayerModel.calculateXtContribution(
                link, physicalLayerConfig, circuit);
            for (int adjId : core.getAdjacentCores()) {
                Core adjCore = link.getCore(adjId);
                if (adjCore == null) continue;
                for (int s = circuit.getStartSlot(); s <= circuit.getEndSlot(); s++) {
                    if (add) adjCore.addXtNoise(s, xtContribution);
                    else adjCore.removeXtNoise(s, xtContribution);
                }
            }
        }
    }
}
