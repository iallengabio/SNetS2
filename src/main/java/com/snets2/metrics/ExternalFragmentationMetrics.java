package com.snets2.metrics;

import com.snets2.model.*;
import com.snets2.output.SimulationResult;
import java.util.*;

/**
2:  * Tracks the external fragmentation of the network.
3:  * Uses a Time-Weighted Average for vertical (link) fragmentation,
4:  * and a per-setup sample for horizontal (path) fragmentation.
5:  */
public class ExternalFragmentationMetrics {
    private double lastObservationTime = 0;
    private double totalSimulationTime = 0;

    public void setLastObservationTime(double lastObservationTime) {
        this.lastObservationTime = lastObservationTime;
    }

    private double weightedExternalFragVerticalGen = 0;
    private double weightedEntropyExternalFragVerticalGen = 0;

    private final Map<String, Double> weightedExternalFragPerLink = new HashMap<>();
    private final Map<String, Double> weightedEntropyExternalFragPerLink = new HashMap<>();

    // Horizontal Fragmentation (only collected on successful circuit setups)
    private double sumExternalFragHorizontal = 0;
    private long horizontalCount = 0;

    public void recordObservation(ControlPlane cp, double currentTime) {
        double deltaT = currentTime - lastObservationTime;
        if (deltaT <= 0) {
            lastObservationTime = currentTime;
            return;
        }

        double verticalFragSum = 0;
        double entropyFragSum = 0;
        int numLinks = cp.getLinks().size();

        for (Link link : cp.getLinks()) {
            String linkId = link.getSourceId() + "->" + link.getDestinationId();
            double linkFragSum = 0;
            double linkEntropySum = 0;
            int numCores = link.getCores().size();

            for (Core core : link.getCores().values()) {
                List<int[]> freeBands = getFreeSpectrumBands(core.getSpectrum());
                linkFragSum += calculateExternalFragmentation(freeBands);
                linkEntropySum += calculateEntropyExternalFragmentation(freeBands, core.getSpectrum().getNumSlots());
            }

            double avgLinkFrag = linkFragSum / numCores;
            double avgLinkEntropy = linkEntropySum / numCores;

            weightedExternalFragPerLink.merge(linkId, avgLinkFrag * deltaT, Double::sum);
            weightedEntropyExternalFragPerLink.merge(linkId, avgLinkEntropy * deltaT, Double::sum);

            verticalFragSum += avgLinkFrag;
            entropyFragSum += avgLinkEntropy;
        }

        weightedExternalFragVerticalGen += (verticalFragSum / numLinks) * deltaT;
        weightedEntropyExternalFragVerticalGen += (entropyFragSum / numLinks) * deltaT;

        totalSimulationTime += deltaT;
        lastObservationTime = currentTime;
    }

    public void recordCircuitSetup(Circuit circuit) {
        List<Link> links = circuit.getPath();
        if (links.isEmpty()) return;

        int coreId = circuit.getCoreIndices().get(0);
        int numSlots = links.get(0).getCore(coreId).getSpectrum().getNumSlots();

        BitSet combined = (BitSet) links.get(0).getCore(coreId).getSpectrum().getSlots().clone();
        for (int i = 1; i < links.size(); i++) {
            combined.or(links.get(i).getCore(coreId).getSpectrum().getSlots());
        }

        List<int[]> freeBands = getFreeSpectrumBandsFromBitSet(combined, numSlots);
        double horizontalFrag = calculateExternalFragmentation(freeBands);

        sumExternalFragHorizontal += horizontalFrag;
        horizontalCount++;
    }

    private List<int[]> getFreeSpectrumBands(Spectrum spectrum) {
        return getFreeSpectrumBandsFromBitSet(spectrum.getSlots(), spectrum.getNumSlots());
    }

    public static List<int[]> getFreeSpectrumBandsFromBitSet(BitSet slots, int numSlots) {
        List<int[]> freeBands = new ArrayList<>();
        int start = 0;
        while (start < numSlots) {
            int nextSet = slots.nextSetBit(start);
            if (nextSet == -1) {
                freeBands.add(new int[]{start, numSlots - 1});
                break;
            }
            if (nextSet > start) {
                freeBands.add(new int[]{start, nextSet - 1});
            }
            start = slots.nextClearBit(nextSet);
        }
        return freeBands;
    }

    public static double calculateExternalFragmentation(List<int[]> freeBands) {
        int totalFreeSlots = 0;
        int maxFreeBandSize = 0;
        for (int[] band : freeBands) {
            int size = band[1] - band[0] + 1;
            totalFreeSlots += size;
            if (size > maxFreeBandSize) {
                maxFreeBandSize = size;
            }
        }
        if (totalFreeSlots == 0) {
            return 0.0;
        }
        return 1.0 - ((double) maxFreeBandSize / totalFreeSlots);
    }

    public static double calculateEntropyExternalFragmentation(List<int[]> freeBands, int numSlots) {
        int totalFreeSlots = 0;
        for (int[] band : freeBands) {
            totalFreeSlots += band[1] - band[0] + 1;
        }
        if (totalFreeSlots == 0) {
            return 0.0;
        }
        double entropy = 0.0;
        for (int[] band : freeBands) {
            int size = band[1] - band[0] + 1;
            double pi = (double) size / totalFreeSlots;
            entropy -= pi * Math.log(pi);
        }
        double maxEntropy = Math.log(numSlots);
        return maxEntropy == 0 ? 0.0 : entropy / maxEntropy;
    }

    public void fillResults(SimulationResult result, Map<String, Object> scenario, int repId) {
        String sheet = "ExternalFragmentation";
        
        // Vertical Gen
        if (totalSimulationTime > 0) {
            result.addValue(sheet, "External Fragmentation Vertical", Map.of("link", "all"), scenario, repId, weightedExternalFragVerticalGen / totalSimulationTime);
            result.addValue(sheet, "Entropy External Fragmentation Vertical", Map.of("link", "all"), scenario, repId, weightedEntropyExternalFragVerticalGen / totalSimulationTime);

            for (String linkId : weightedExternalFragPerLink.keySet()) {
                double avgLinkFrag = weightedExternalFragPerLink.get(linkId) / totalSimulationTime;
                double avgLinkEntropy = weightedEntropyExternalFragPerLink.get(linkId) / totalSimulationTime;
                result.addValue(sheet, "External Fragmentation per Link", Map.of("link", linkId), scenario, repId, avgLinkFrag);
                result.addValue(sheet, "Entropy External Fragmentation per Link", Map.of("link", linkId), scenario, repId, avgLinkEntropy);
            }
        }

        // Horizontal Gen
        if (horizontalCount > 0) {
            result.addValue(sheet, "External Fragmentation Horizontal", Map.of("link", "route"), scenario, repId, sumExternalFragHorizontal / horizontalCount);
        }
    }
}
