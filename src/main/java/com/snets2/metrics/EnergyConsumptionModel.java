package com.snets2.metrics;

import com.snets2.model.*;
import java.util.HashMap;
import java.util.Map;

/**
 * Utility class for calculating energy consumption based on hardware parameters.
 *
 * <p>OXC and transponder power follow J. L. Vizcaino, Y. Ye, I. Tafur Monroy, "Energy efficiency analysis for
 * flexible-grid OFDM-based optical networks", Computer Networks 56(10), 2012. The EDFA (100 W) and installed
 * regenerator (80 W) values are simulator assumptions. Equations and assumptions in
 * docs/formal_description/06_output_metrics.md, Section 3.6.</p>
 */
public class EnergyConsumptionModel {

    /** Power of one optical amplifier (EDFA), W. */
    public static final double AMPLIFIER_POWER_W = 100.0;
    /** OXC power per node degree (term 85 n), W. */
    public static final double OXC_POWER_PER_DEGREE_W = 85.0;
    /** OXC power per add/drop port (term 100 a), W. */
    public static final double OXC_POWER_PER_ADD_DROP_PORT_W = 100.0;
    /** Fixed OXC power (term 150), W. */
    public static final double OXC_BASE_POWER_W = 150.0;
    /** Static power of an installed regenerator, charged whether or not it is in use, W. */
    public static final double REGENERATOR_IDLE_POWER_W = 80.0;
    /** Transponder power per Gbps of line rate of one slot: PC_ofdm = 1.683 TR, W/Gbps. */
    public static final double TRANSPONDER_POWER_PER_GBPS_W = 1.683;
    /** Fixed transponder power (term 91.333 of PC_tran), W. */
    public static final double TRANSPONDER_BASE_POWER_W = 91.333;

    /**
     * Calculates the static power consumption of the entire network (OXCs, EDFAs, and idle Regenerators):
     * <pre>
     * P_static = sum_links sum_amplifiers P_amp + sum_nodes (85 n + 100 a + 150 + 80 r)
     * </pre>
     * where n is the node degree (number of directed links incident to the node, in and out), a its add/drop
     * degree ({@link Node#getAddDropDegree()}, independent of the installed transceivers, whose power is dynamic)
     * and r the number of installed regenerators. The amplifiers are those of {@link Link#getAmplifiers()}, i.e.
     * the same booster + N_l line + pre-amplifier chain used by the ASE model
     * ({@link PhysicalLayerModel#amplifierChainGainsDb}).
     *
     * @param topology The network topology.
     * @return Total static power in Watts.
     */
    public static double calculateStaticPower(NetworkTopology topology) {
        double totalEDFAPower = 0;
        for (Link link : topology.links()) {
            for (Amplifier amplifier : link.getAmplifiers()) {
                totalEDFAPower += amplifier.getPowerConsumption();
            }
        }

        double totalOXCPower = 0;
        Map<String, Integer> nodeDegrees = calculateNodeDegrees(topology);

        for (Node node : topology.nodes()) {
            totalOXCPower += oxcPower(nodeDegrees.getOrDefault(node.getId(), 0), node.getAddDropDegree())
                    + node.getTotalRegenerators() * REGENERATOR_IDLE_POWER_W;
        }

        return totalEDFAPower + totalOXCPower;
    }

    /**
     * Power of an OXC (Vizcaino et al., 2012): 85 n + 100 a + 150 W.
     *
     * @param degree        Node degree n.
     * @param addDropDegree Add/drop degree a.
     * @return OXC power in Watts.
     */
    public static double oxcPower(int degree, int addDropDegree) {
        return degree * OXC_POWER_PER_DEGREE_W + addDropDegree * OXC_POWER_PER_ADD_DROP_PORT_W + OXC_BASE_POWER_W;
    }

    /**
     * Calculates the dynamic power consumption of a specific circuit (BVTs + Regenerators).
     *
     * @param circuit       The active circuit.
     * @param slotBandwidth The bandwidth of a single slot in Hz.
     * @return Power consumed by the circuit's transponders and active regenerators in Watts.
     */
    public static double calculateCircuitPower(Circuit circuit, double slotBandwidth) {
        // tr (Gbps) = (fs * log2(M)) / 1.0E+9
        double tr = (slotBandwidth * (Math.log(circuit.getModulation().m()) / Math.log(2))) / 1.0E9;

        // PCofdm = 1.683 * tr
        double PCofdm = TRANSPONDER_POWER_PER_GBPS_W * tr;

        // PCtran = numSlots * PCofdm + 91.333
        int numSlots = circuit.getEndSlot() - circuit.getStartSlot() + 1;
        double PCtran = (numSlots * PCofdm) + TRANSPONDER_BASE_POWER_W;

        // Each circuit uses 2 transponders (Source Tx and Destination Rx) plus 2 transponders per regenerator
        int numRegenerators = circuit.getRegeneratorNodes().size();
        return 2.0 * (1 + numRegenerators) * PCtran;
    }

    private static Map<String, Integer> calculateNodeDegrees(NetworkTopology topology) {
        Map<String, Integer> degrees = new HashMap<>();
        for (Link link : topology.links()) {
            degrees.merge(link.getSourceId(), 1, Integer::sum);
            degrees.merge(link.getDestinationId(), 1, Integer::sum);
        }
        return degrees;
    }
}
