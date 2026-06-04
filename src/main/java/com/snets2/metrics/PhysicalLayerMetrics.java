package com.snets2.metrics;

import com.snets2.output.SimulationResult;
import java.util.HashMap;
import java.util.Map;

/**
 * Collects statistics about the physical layer quality of established circuits.
 */
public class PhysicalLayerMetrics {

    // Global accumulators
    private double sumOsnr = 0;
    private double sumXt = 0;
    private double sumPower = 0;
    private long circuitCount = 0;

    // Per node pair statistics (src-dest)
    private final Map<String, Double> pairSumOsnr = new HashMap<>();
    private final Map<String, Double> pairSumXt = new HashMap<>();
    private final Map<String, Double> pairSumPower = new HashMap<>();
    private final Map<String, Long> pairCount = new HashMap<>();

    // Crosstalk per overlaps statistics
    private final Map<Integer, Double> overlapSumXt = new HashMap<>();
    private final Map<Integer, Double> overlapMinXt = new HashMap<>();
    private final Map<Integer, Double> overlapMaxXt = new HashMap<>();
    private final Map<Integer, Long> overlapCount = new HashMap<>();

    /**
     * Records the physical properties of a newly established circuit.
     * 
     * @param src      Source node ID.
     * @param dest     Destination node ID.
     * @param osnr     OSNR in dB.
     * @param xt       Crosstalk in dB.
     * @param power    Launch power in dBm.
     * @param overlaps Number of overlapping slots with adjacent cores.
     */
    public void recordCircuitSetup(String src, String dest, double osnr, double xt, double power, int overlaps) {
        // 1. Global
        sumOsnr += osnr;
        sumXt += xt;
        sumPower += power;
        circuitCount++;

        // 2. Per Pair
        String pairKey = src + "-" + dest;
        pairSumOsnr.merge(pairKey, osnr, Double::sum);
        pairSumXt.merge(pairKey, xt, Double::sum);
        pairSumPower.merge(pairKey, power, Double::sum);
        pairCount.merge(pairKey, 1L, Long::sum);

        // 3. Per Overlap (XT)
        overlapSumXt.merge(overlaps, xt, Double::sum);
        overlapCount.merge(overlaps, 1L, Long::sum);
        
        overlapMinXt.merge(overlaps, xt, Math::min);
        overlapMaxXt.merge(overlaps, xt, Math::max);
    }

    public void fillResults(SimulationResult result, Map<String, Object> scenario, int repId) {
        String sheet = "PhysicalLayerStatistics";
        
        if (circuitCount == 0) return;

        // --- General Metrics ---
        Map<String, String> generalDims = Map.of("src", "all", "dest", "all", "overlaps", "all");
        result.addValue(sheet, "Average OSNR (dB)", generalDims, scenario, repId, sumOsnr / circuitCount);
        result.addValue(sheet, "Average XT (dB)", generalDims, scenario, repId, sumXt / circuitCount);
        result.addValue(sheet, "Average Power (dBm)", generalDims, scenario, repId, sumPower / circuitCount);

        // --- Per Pair Metrics ---
        for (String pair : pairCount.keySet()) {
            String[] nodes = pair.split("-");
            Map<String, String> dims = Map.of("src", nodes[0], "dest", nodes[1], "overlaps", "all");
            long count = pairCount.get(pair);
            result.addValue(sheet, "Average OSNR (dB) per pair", dims, scenario, repId, pairSumOsnr.get(pair) / count);
            result.addValue(sheet, "Average XT (dB) per pair", dims, scenario, repId, pairSumXt.get(pair) / count);
            result.addValue(sheet, "Average Power (dBm) per pair", dims, scenario, repId, pairSumPower.get(pair) / count);
        }

        // --- Per Overlap Metrics ---
        for (Integer overlaps : overlapCount.keySet()) {
            Map<String, String> dims = Map.of("src", "all", "dest", "all", "overlaps", String.valueOf(overlaps));
            long count = overlapCount.get(overlaps);
            result.addValue(sheet, "Average XT (dB) per overlaps", dims, scenario, repId, overlapSumXt.get(overlaps) / count);
            result.addValue(sheet, "Min XT (dB) per overlaps", dims, scenario, repId, overlapMinXt.get(overlaps));
            result.addValue(sheet, "Max XT (dB) per overlaps", dims, scenario, repId, overlapMaxXt.get(overlaps));
        }
    }
}
