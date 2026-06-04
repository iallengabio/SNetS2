package com.snets2.metrics;

import com.snets2.model.*;
import com.snets2.output.SimulationResult;
import java.util.*;

/**
2:  * Tracks the relative fragmentation of EON links.
3:  * Evaluates the fraction of unusable free slots for a demand of size c.
4:  */
public class RelativeFragmentationMetrics {
    private double lastObservationTime = 0;
    private double totalSimulationTime = 0;

    private final Set<Integer> possibleSlotSizes = new TreeSet<>();
    private final Map<Integer, Double> weightedRelativeFragGen = new HashMap<>();

    /**
     * Pre-calculates possible slot demand sizes based on modulations and bit rates.
     */
    public void initialize(ControlPlane cp, List<Double> bitRates) {
        for (double bitRate : bitRates) {
            for (ModulationFormat mod : cp.getTopology().modulations()) {
                int bitsPerSymbol = mod.getBitsPerSymbol();
                int numSlots = (int) Math.ceil((bitRate * 1E9) / (bitsPerSymbol * cp.getSlotBandwidth()));
                numSlots += cp.getGuardBand();
                possibleSlotSizes.add(numSlots);
            }
        }
    }

    public void recordObservation(ControlPlane cp, double currentTime) {
        double deltaT = currentTime - lastObservationTime;
        if (deltaT <= 0) {
            lastObservationTime = currentTime;
            return;
        }

        int numLinks = cp.getLinks().size();

        for (Integer c : possibleSlotSizes) {
            double totalFragForC = 0;
            for (Link link : cp.getLinks()) {
                double linkFragSum = 0;
                int numCores = link.getCores().size();
                for (Core core : link.getCores().values()) {
                    List<int[]> freeBands = ExternalFragmentationMetrics.getFreeSpectrumBandsFromBitSet(
                        core.getSpectrum().getSlots(), core.getSpectrum().getNumSlots()
                    );
                    linkFragSum += calculateRelativeFragmentation(freeBands, c);
                }
                totalFragForC += linkFragSum / numCores;
            }
            double avgFragForC = totalFragForC / numLinks;
            weightedRelativeFragGen.merge(c, avgFragForC * deltaT, Double::sum);
        }

        totalSimulationTime += deltaT;
        lastObservationTime = currentTime;
    }

    public static double calculateRelativeFragmentation(List<int[]> freeBands, int c) {
        int totalFreeSlots = 0;
        int unusableFreeSlots = 0;
        for (int[] band : freeBands) {
            int size = band[1] - band[0] + 1;
            totalFreeSlots += size;
            if (size < c) {
                unusableFreeSlots += size;
            }
        }
        if (totalFreeSlots == 0) {
            return 0.0;
        }
        return (double) unusableFreeSlots / totalFreeSlots;
    }

    public void fillResults(SimulationResult result, Map<String, Object> scenario, int repId) {
        String sheet = "RelativeFragmentation";
        if (totalSimulationTime == 0) return;

        for (Integer c : possibleSlotSizes) {
            double avgFrag = weightedRelativeFragGen.getOrDefault(c, 0.0) / totalSimulationTime;
            result.addValue(sheet, "Average Relative Fragmentation", Map.of("c", String.valueOf(c)), scenario, repId, avgFrag);
        }
    }
}
