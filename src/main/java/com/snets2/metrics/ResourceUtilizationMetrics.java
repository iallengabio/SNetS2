package com.snets2.metrics;

import com.snets2.model.ControlPlane;
import com.snets2.model.Core;
import com.snets2.model.Link;
import java.util.HashMap;
import java.util.Map;

/**
 * Tracks the utilization of network resources using a Time-Weighted Average approach.
 */
public class ResourceUtilizationMetrics {
    
    private double lastObservationTime = 0;
    private double totalSimulationTime = 0;

    private double weightedUtilizationGen = 0;
    private final Map<String, Double> weightedUtilizationPerLink = new HashMap<>();
    private final Map<Integer, Double> weightedUtilizationPerCore = new HashMap<>();
    private final Map<String, Map<Integer, Double>> weightedUtilizationPerLinkCore = new HashMap<>();
    private double[] weightedUtilizationPerSlot;

    private int numberObservations = 0;

    public void recordObservation(ControlPlane cp, double currentTime) {
        double deltaT = currentTime - lastObservationTime;
        numberObservations++;
        
        if (deltaT <= 0) {
            lastObservationTime = currentTime;
            return;
        }

        if (weightedUtilizationPerSlot == null) {
            int numSlots = cp.getLinks().get(0).getCore(0).getSpectrum().getNumSlots();
            weightedUtilizationPerSlot = new double[numSlots];
        }

        long totalSlotsNetwork = 0;
        long occupiedSlotsNetwork = 0;
        int numLinks = cp.getLinks().size();

        for (Link link : cp.getLinks()) {
            double occupiedInLink = 0;
            int slotsInLink = 0;
            String linkId = link.getSourceId() + "->" + link.getDestinationId();

            for (Core core : link.getCores().values()) {
                int numSlots = core.getSpectrum().getNumSlots();
                double occupiedInCore = 0;

                for (int s = 0; s < numSlots; s++) {
                    if (core.getSpectrum().isOccupied(s)) {
                        occupiedInCore++;
                        occupiedInLink++;
                        occupiedSlotsNetwork++;
                        weightedUtilizationPerSlot[s] += deltaT;
                    }
                    slotsInLink++;
                    totalSlotsNetwork++;
                }

                double utilCore = occupiedInCore / numSlots;
                // Weighted average per core ID across all links
                weightedUtilizationPerCore.merge(core.getId(), (utilCore / numLinks) * deltaT, Double::sum);
                
                weightedUtilizationPerLinkCore.computeIfAbsent(linkId, k -> new HashMap<>())
                                              .merge(core.getId(), utilCore * deltaT, Double::sum);
            }
            double utilLink = occupiedInLink / slotsInLink;
            weightedUtilizationPerLink.merge(linkId, utilLink * deltaT, Double::sum);
        }

        double utilGen = (double) occupiedSlotsNetwork / totalSlotsNetwork;
        weightedUtilizationGen += utilGen * deltaT;

        totalSimulationTime += deltaT;
        lastObservationTime = currentTime;
    }

    public double getAverageGeneralUtilization() {
        return totalSimulationTime == 0 ? 0 : weightedUtilizationGen / totalSimulationTime;
    }

    public Map<String, Double> getAverageUtilizationPerLink() {
        Map<String, Double> result = new HashMap<>();
        weightedUtilizationPerLink.forEach((k, v) -> result.put(k, v / totalSimulationTime));
        return result;
    }

    public Map<Integer, Double> getAverageUtilizationPerCore() {
        Map<Integer, Double> result = new HashMap<>();
        weightedUtilizationPerCore.forEach((k, v) -> result.put(k, v / totalSimulationTime));
        return result;
    }

    public double[] getAverageUtilizationPerSlot() {
        if (weightedUtilizationPerSlot == null) return new double[0];
        double[] result = new double[weightedUtilizationPerSlot.length];
        // The probability is weighted by total simulation time
        for (int i = 0; i < result.length; i++) {
            result[i] = weightedUtilizationPerSlot[i] / totalSimulationTime;
        }
        return result;
    }

    public int getNumberObservations() { return numberObservations; }

    public void fillResults(com.snets2.output.SimulationResult result, Map<String, Object> scenario, int repId) {
        String sheet = "SpectrumUtilization";
        result.addValue(sheet, "General Utilization", Map.of("entity", "network"), scenario, repId, getAverageGeneralUtilization());

        Map<String, Double> linkUtils = getAverageUtilizationPerLink();
        for (String linkKey : linkUtils.keySet()) {
            result.addValue(sheet, "Utilization per Link", Map.of("entity", linkKey), scenario, repId, linkUtils.get(linkKey));
        }

        Map<Integer, Double> coreUtils = getAverageUtilizationPerCore();
        for (Integer coreId : coreUtils.keySet()) {
            result.addValue(sheet, "Utilization per Core", Map.of("entity", "core_" + coreId), scenario, repId, coreUtils.get(coreId));
        }
    }
}
