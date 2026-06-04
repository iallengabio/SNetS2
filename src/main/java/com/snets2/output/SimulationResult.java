package com.snets2.output;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Container to aggregate all metrics collected during an experimental run.
 * Ensures that different scenarios (parameter sweeps) occupy different rows.
 */
public class SimulationResult {
    
    // Using TreeMap for Sheet order and another Map for Rows
    private final Map<String, Map<String, MetricRow>> data = new TreeMap<>();
    private final int totalReplications;

    public SimulationResult(int totalReplications) {
        this.totalReplications = totalReplications;
    }

    /**
     * Adds a value for a specific metric in a specific replication.
     * The row is identified by the combination of Scenario + SubMetric + Dimensions.
     */
    public synchronized void addValue(String sheet, String subMetric, Map<String, String> dimensions, 
                         Map<String, Object> scenario, int repId, double value) {
        
        Map<String, MetricRow> sheetData = data.computeIfAbsent(sheet, k -> new HashMap<>());
        
        // --- FIX: Include Scenario in the Row Key to prevent overwriting different loads ---
        String rowKey = scenario.toString() + "_" + subMetric + "_" + dimensions.toString();
        
        MetricRow row = sheetData.computeIfAbsent(rowKey, k -> new MetricRow(subMetric, dimensions, scenario));
        row.addRepValue(repId, value);
    }

    public Map<String, Map<String, MetricRow>> getData() {
        return data;
    }

    public int getTotalReplications() {
        return totalReplications;
    }

    public static class MetricRow {
        private final String subMetric;
        private final Map<String, String> dimensions;
        private final Map<String, Object> scenario;
        private final Map<Integer, Double> repValues = new HashMap<>();

        public MetricRow(String subMetric, Map<String, String> dimensions, Map<String, Object> scenario) {
            this.subMetric = subMetric;
            this.dimensions = dimensions;
            this.scenario = scenario;
        }

        public void addRepValue(int repId, double value) {
            repValues.put(repId, value);
        }

        public String getSubMetric() { return subMetric; }
        public Map<String, String> getDimensions() { return dimensions; }
        public Map<String, Object> getScenario() { return scenario; }
        public Map<Integer, Double> getRepValues() { return repValues; }
    }
}
