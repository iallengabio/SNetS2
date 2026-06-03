package com.snets2.metrics;

import com.snets2.model.*;
import java.util.HashMap;
import java.util.Map;

/**
 * Utility class for calculating energy consumption based on hardware parameters.
 */
public class EnergyConsumptionModel {

    /**
     * Calculates the static power consumption of the entire network (OXCs and EDFAs).
     * 
     * @param topology The network topology.
     * @return Total static power in Watts.
     */
    public static double calculateStaticPower(NetworkTopology topology) {
        double totalEDFAPower = 0;
        for (Link link : topology.links()) {
            totalEDFAPower += link.getAmplifiers().size() * 100.0;
        }

        double totalOXCPower = 0;
        Map<String, Integer> nodeDegrees = calculateNodeDegrees(topology);
        
        for (Node node : topology.nodes()) {
            int n = nodeDegrees.getOrDefault(node.getId(), 0);
            int a = node.getTotalTx() + node.getTotalRx();
            totalOXCPower += (n * 85.0) + (a * 100.0) + 150.0;
        }

        return totalEDFAPower + totalOXCPower;
    }

    /**
     * Calculates the dynamic power consumption of a specific circuit (BVTs).
     * 
     * @param circuit       The active circuit.
     * @param slotBandwidth The bandwidth of a single slot in Hz.
     * @return Power consumed by the circuit's transponders in Watts.
     */
    public static double calculateCircuitPower(Circuit circuit, double slotBandwidth) {
        // tr (Gbps) = (fs * log2(M)) / 1.0E+9
        double tr = (slotBandwidth * (Math.log(circuit.getModulation().m()) / Math.log(2))) / 1.0E9;
        
        // PCofdm = 1.683 * tr
        double PCofdm = 1.683 * tr;
        
        // PCtran = numSlots * PCofdm + 91.333
        int numSlots = circuit.getEndSlot() - circuit.getStartSlot() + 1;
        double PCtran = (numSlots * PCofdm) + 91.333;
        
        // Each circuit uses 2 transponders (Source Tx and Destination Rx)
        return 2.0 * PCtran;
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
