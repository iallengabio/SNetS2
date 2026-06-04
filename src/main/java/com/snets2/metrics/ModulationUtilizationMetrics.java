package com.snets2.metrics;

import com.snets2.model.Circuit;
import com.snets2.output.SimulationResult;
import java.util.HashMap;
import java.util.Map;

/**
2:  * Tracks modulation format utilization (percentages) globally and per bit rate.
3:  */
public class ModulationUtilizationMetrics {
    private long totalObservations = 0;
    private final Map<String, Long> countPerMod = new HashMap<>();
    private final Map<String, Map<Double, Long>> countPerModPerBw = new HashMap<>();

    public void recordCircuitSetup(Circuit circuit) {
        String modName = circuit.getModulation().name();
        double bitRate = circuit.getBitRate();

        totalObservations++;
        countPerMod.merge(modName, 1L, Long::sum);
        countPerModPerBw.computeIfAbsent(modName, k -> new HashMap<>())
                        .merge(bitRate, 1L, Long::sum);
    }

    public void fillResults(SimulationResult result, Map<String, Object> scenario, int repId) {
        String sheet = "ModulationUtilization";
        if (totalObservations == 0) return;

        for (String modName : countPerMod.keySet()) {
            double percent = (double) countPerMod.get(modName) / totalObservations;
            result.addValue(sheet, "Percentage per Modulation", Map.of("modulation", modName, "bitrate", "all"), scenario, repId, percent);

            Map<Double, Long> bwMap = countPerModPerBw.get(modName);
            if (bwMap != null) {
                for (Double bw : bwMap.keySet()) {
                    double percentBw = (double) bwMap.get(bw) / totalObservations;
                    result.addValue(sheet, "Percentage per Modulation and BitRate", 
                        Map.of("modulation", modName, "bitrate", String.valueOf(bw)), scenario, repId, percentBw);
                }
            }
        }
    }
}
