package com.snets2.metrics;

import com.snets2.model.ControlPlane;
import com.snets2.model.Node;
import com.snets2.output.SimulationResult;
import java.util.HashMap;
import java.util.Map;

/**
2:  * Tracks time-weighted average Tx, Rx, and regenerator utilization generally and per node.
3:  * Tracks peak usage for all nodes.
4:  */
public class TransmittersReceiversRegeneratorsUtilizationMetrics {
    private double lastObservationTime = 0;
    private double totalSimulationTime = 0;

    public void setLastObservationTime(double lastObservationTime) {
        this.lastObservationTime = lastObservationTime;
    }

    private double weightedTxUtilizationGen = 0;
    private double weightedRxUtilizationGen = 0;
    private double weightedRegenUtilizationGen = 0;

    private final Map<String, Double> weightedTxPerNode = new HashMap<>();
    private final Map<String, Double> weightedRxPerNode = new HashMap<>();
    private final Map<String, Double> weightedRegenPerNode = new HashMap<>();

    private final Map<String, Integer> maxTxPerNode = new HashMap<>();
    private final Map<String, Integer> maxRxPerNode = new HashMap<>();
    private final Map<String, Integer> maxRegenPerNode = new HashMap<>();

    public void recordObservation(ControlPlane cp, double currentTime) {
        double deltaT = currentTime - lastObservationTime;
        if (deltaT <= 0) {
            lastObservationTime = currentTime;
            return;
        }

        double txSum = 0;
        double rxSum = 0;
        double regenSum = 0;
        int numNodes = cp.getNodes().size();

        for (Node node : cp.getNodes()) {
            int txUsed = node.getTotalTx() - node.getAvailableTx();
            int rxUsed = node.getTotalRx() - node.getAvailableRx();
            int regenUsed = node.getTotalRegenerators() - node.getAvailableRegenerators();

            txSum += txUsed;
            rxSum += rxUsed;
            regenSum += regenUsed;

            weightedTxPerNode.merge(node.getId(), (double) txUsed * deltaT, Double::sum);
            weightedRxPerNode.merge(node.getId(), (double) rxUsed * deltaT, Double::sum);
            weightedRegenPerNode.merge(node.getId(), (double) regenUsed * deltaT, Double::sum);

            maxTxPerNode.merge(node.getId(), txUsed, Math::max);
            maxRxPerNode.merge(node.getId(), rxUsed, Math::max);
            maxRegenPerNode.merge(node.getId(), regenUsed, Math::max);
        }

        weightedTxUtilizationGen += (txSum / numNodes) * deltaT;
        weightedRxUtilizationGen += (rxSum / numNodes) * deltaT;
        weightedRegenUtilizationGen += (regenSum / numNodes) * deltaT;

        totalSimulationTime += deltaT;
        lastObservationTime = currentTime;
    }

    public void fillResults(SimulationResult result, Map<String, Object> scenario, int repId) {
        String sheet = "TransmittersReceiversRegeneratorsUtilization";
        if (totalSimulationTime == 0) return;

        // General (Average across all nodes)
        result.addValue(sheet, "Average Tx Utilization General", Map.of("node", "all"), scenario, repId, weightedTxUtilizationGen / totalSimulationTime);
        result.addValue(sheet, "Average Rx Utilization General", Map.of("node", "all"), scenario, repId, weightedRxUtilizationGen / totalSimulationTime);
        result.addValue(sheet, "Average Regen Utilization General", Map.of("node", "all"), scenario, repId, weightedRegenUtilizationGen / totalSimulationTime);

        // Per Node
        for (String nodeId : weightedTxPerNode.keySet()) {
            double avgTx = weightedTxPerNode.get(nodeId) / totalSimulationTime;
            double avgRx = weightedRxPerNode.get(nodeId) / totalSimulationTime;
            double avgRegen = weightedRegenPerNode.get(nodeId) / totalSimulationTime;

            result.addValue(sheet, "Average Tx Utilization", Map.of("node", nodeId), scenario, repId, avgTx);
            result.addValue(sheet, "Average Rx Utilization", Map.of("node", nodeId), scenario, repId, avgRx);
            result.addValue(sheet, "Average Regen Utilization", Map.of("node", nodeId), scenario, repId, avgRegen);

            result.addValue(sheet, "Max Tx Utilization", Map.of("node", nodeId), scenario, repId, maxTxPerNode.getOrDefault(nodeId, 0));
            result.addValue(sheet, "Max Rx Utilization", Map.of("node", nodeId), scenario, repId, maxRxPerNode.getOrDefault(nodeId, 0));
            result.addValue(sheet, "Max Regen Utilization", Map.of("node", nodeId), scenario, repId, maxRegenPerNode.getOrDefault(nodeId, 0));
        }
    }
}
