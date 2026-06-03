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
    private final Map<Integer, Double> requestedBitRatePerCore = new HashMap<>();
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
     */
    public void fillResults(com.snets2.output.SimulationResult result, Map<String, Object> scenario, int repId) {
        String sheet = "BlockingProbability";
        
        // General BP
        result.addValue(sheet, "General Bit Rate BP", Map.of("src", "all", "dest", "all"), scenario, repId, getGeneralBlockingProbability());
        
        // Causes
        result.addValue(sheet, "BP by Fragmentation", Map.of("src", "all", "dest", "all"), scenario, repId, generalRequestedBitRate == 0 ? 0 : bitRateBlockingByFragmentation / generalRequestedBitRate);
        result.addValue(sheet, "BP by Lack of Tx", Map.of("src", "all", "dest", "all"), scenario, repId, generalRequestedBitRate == 0 ? 0 : bitRateBlockingByLackTransmitters / generalRequestedBitRate);
        result.addValue(sheet, "BP by Lack of Rx", Map.of("src", "all", "dest", "all"), scenario, repId, generalRequestedBitRate == 0 ? 0 : bitRateBlockingByLackReceivers / generalRequestedBitRate);
        
        // Per Pair
        for (String pair : requestedBitRatePerPair.keySet()) {
            double req = requestedBitRatePerPair.get(pair);
            double block = bitRateBlockedPerPair.getOrDefault(pair, 0.0);
            String[] nodes = pair.split("-");
            result.addValue(sheet, "BP per pair", Map.of("src", nodes[0], "dest", nodes[1]), scenario, repId, block / req);
        }
    }
}
