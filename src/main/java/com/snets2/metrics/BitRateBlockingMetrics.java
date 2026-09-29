package com.snets2.metrics;

import java.util.HashMap;
import java.util.Map;

/**
 * Handles the collection and calculation of Bit Rate Blocking metrics.
 */
public class BitRateBlockingMetrics {

    // --- Raw Sums for Calculation ---
    private double generalRequestedBitRate = 0;
    private double generalBlockedBitRate = 0;

    // Blocking breakdown by cause
    private double bitRateBlockingByFragmentation = 0;
    private double bitRateBlockingByLackTransmitters = 0;
    private double bitRateBlockingByLackReceivers = 0;
    private double bitRateBlockingByQoTN = 0;
    private double bitRateBlockingByQoTO = 0;
    private double bitRateBlockingByXt = 0;
    private double bitRateBlockingByXtOther = 0;
    private double bitRateBlockingByOther = 0;

    // Breakdown per Core
    private final Map<Integer, Double> bitRateBlockedPerCore = new HashMap<>();

    // Breakdown per Node Pair (src-dest)
    private final Map<String, Double> requestedBitRatePerPair = new HashMap<>();
    private final Map<String, Double> bitRateBlockedPerPair = new HashMap<>();

    // Breakdown per Bandwidth (Requested Bit Rate value)
    private final Map<Double, Double> requestedBitRatePerBW = new HashMap<>();
    private final Map<Double, Double> bitRateBlockedPerBW = new HashMap<>();

    // Breakdown per Pair and Bandwidth
    private final Map<String, Map<Double, Double>> requestedBitRatePairBR = new HashMap<>();
    private final Map<String, Map<Double, Double>> bitRateBlockedPairBR = new HashMap<>();

    /**
     * Records a new arrival request.
     */
    public void recordArrival(String src, String dest, double bitRate) {
        generalRequestedBitRate += bitRate;
        
        String pairKey = src + "-" + dest;
        requestedBitRatePerPair.merge(pairKey, bitRate, Double::sum);
        requestedBitRatePerBW.merge(bitRate, bitRate, Double::sum);
        
        requestedBitRatePairBR.computeIfAbsent(pairKey, k -> new HashMap<>())
                             .merge(bitRate, bitRate, Double::sum);
    }

    /**
     * Records a blocking event.
     */
    public void recordBlock(String src, String dest, double bitRate, BlockingCause cause, Integer coreId) {
        generalBlockedBitRate += bitRate;
        
        // 1. Breakdown by cause
        switch (cause) {
            case FRAGMENTATION -> bitRateBlockingByFragmentation += bitRate;
            case LACK_OF_TRANSMITTERS -> bitRateBlockingByLackTransmitters += bitRate;
            case LACK_OF_RECEIVERS -> bitRateBlockingByLackReceivers += bitRate;
            case QOT_NEW -> bitRateBlockingByQoTN += bitRate;
            case QOT_OTHERS -> bitRateBlockingByQoTO += bitRate;
            case CROSSTALK -> bitRateBlockingByXt += bitRate;
            case XT_OTHERS -> bitRateBlockingByXtOther += bitRate;
            default -> bitRateBlockingByOther += bitRate;
        }

        // 2. Breakdown by core (if applicable)
        if (coreId != null) {
            bitRateBlockedPerCore.merge(coreId, bitRate, Double::sum);
        }

        // 3. Breakdown by pair and BW
        String pairKey = src + "-" + dest;
        bitRateBlockedPerPair.merge(pairKey, bitRate, Double::sum);
        bitRateBlockedPerBW.merge(bitRate, bitRate, Double::sum);
        
        bitRateBlockedPairBR.computeIfAbsent(pairKey, k -> new HashMap<>())
                           .merge(bitRate, bitRate, Double::sum);
    }

    // --- Getters for Probabilities (Calculated on demand) ---

    public double getGeneralBlockingProbability() {
        return generalRequestedBitRate == 0 ? 0 : generalBlockedBitRate / generalRequestedBitRate;
    }

    public double getGeneralRequestedBitRate() { return generalRequestedBitRate; }

    /**
     * Bit-rate blocking probability of each requested bit rate (Gbps). Since every request of a class has
     * the same bit rate, this is also the request blocking probability of that class.
     */
    public Map<Double, Double> getBlockingProbabilityPerBitRate() {
        Map<Double, Double> bp = new java.util.TreeMap<>();
        requestedBitRatePerBW.forEach((bw, req) -> bp.put(bw, bitRateBlockedPerBW.getOrDefault(bw, 0.0) / req));
        return bp;
    }
    public double getBitRateBlockingByFragmentation() { return bitRateBlockingByFragmentation; }
    public double getBitRateBlockingByLackTransmitters() { return bitRateBlockingByLackTransmitters; }
    public double getBitRateBlockingByLackReceivers() { return bitRateBlockingByLackReceivers; }
    public double getBitRateBlockingByQoTN() { return bitRateBlockingByQoTN; }
    public double getBitRateBlockingByQoTO() { return bitRateBlockingByQoTO; }
    public double getBitRateBlockingByXt() { return bitRateBlockingByXt; }
    public double getBitRateBlockingByXtOther() { return bitRateBlockingByXtOther; }
    public double getBitRateBlockingByOther() { return bitRateBlockingByOther; }
    
    /**
     * Fills the provided SimulationResult with the current metrics.
     *
     * <p>Every row of the {@code BlockingProbability} sheet has the same dimension keys
     * ({@code src}, {@code dest}, {@code core}, {@code bitrate}), with {@code "all"} for the dimensions a
     * row does not break down, so that the Excel columns stay consistent.</p>
     */
    public void fillResults(com.snets2.output.SimulationResult result, Map<String, Object> scenario, int repId, int totalCores) {
        String sheet = "BlockingProbability";
        Map<String, String> all = dims("all", "all", "all", "all");

        // General BP
        result.addValue(sheet, "General Bit Rate BP", all, scenario, repId, getGeneralBlockingProbability());

        // Causes
        result.addValue(sheet, "BP by Fragmentation", all, scenario, repId, shareOfRequested(bitRateBlockingByFragmentation));
        result.addValue(sheet, "BP by Lack of Tx", all, scenario, repId, shareOfRequested(bitRateBlockingByLackTransmitters));
        result.addValue(sheet, "BP by Lack of Rx", all, scenario, repId, shareOfRequested(bitRateBlockingByLackReceivers));
        result.addValue(sheet, "BP by QoT New", all, scenario, repId, shareOfRequested(bitRateBlockingByQoTN));
        result.addValue(sheet, "BP by QoT Others", all, scenario, repId, shareOfRequested(bitRateBlockingByQoTO));
        result.addValue(sheet, "BP by Crosstalk", all, scenario, repId, shareOfRequested(bitRateBlockingByXt));
        result.addValue(sheet, "BP by Crosstalk Others", all, scenario, repId, shareOfRequested(bitRateBlockingByXtOther));
        result.addValue(sheet, "BP by Other", all, scenario, repId, shareOfRequested(bitRateBlockingByOther));

        // Per Core BP (for all available cores)
        for (int coreId = 0; coreId < totalCores; coreId++) {
            double block = bitRateBlockedPerCore.getOrDefault(coreId, 0.0);
            result.addValue(sheet, "BP per core", dims("all", "all", String.valueOf(coreId), "all"), scenario, repId, shareOfRequested(block));
        }

        // Per Pair
        for (String pair : requestedBitRatePerPair.keySet()) {
            double req = requestedBitRatePerPair.get(pair);
            double block = bitRateBlockedPerPair.getOrDefault(pair, 0.0);
            String[] nodes = pair.split("-");
            result.addValue(sheet, "BP per pair", dims(nodes[0], nodes[1], "all", "all"), scenario, repId, block / req);
        }

        // Per requested bit rate (Gbps): blocked / requested bit rate of the class (issue #22)
        getBlockingProbabilityPerBitRate().forEach((bitRate, bp) ->
            result.addValue(sheet, "BP per bit rate", dims("all", "all", "all", String.valueOf(bitRate)), scenario, repId, bp));
    }

    private double shareOfRequested(double blockedBitRate) {
        return generalRequestedBitRate == 0 ? 0 : blockedBitRate / generalRequestedBitRate;
    }

    private static Map<String, String> dims(String src, String dest, String core, String bitRate) {
        return Map.of("src", src, "dest", dest, "core", core, "bitrate", bitRate);
    }
}
