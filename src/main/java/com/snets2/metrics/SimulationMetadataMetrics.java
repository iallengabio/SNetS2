package com.snets2.metrics;

import com.snets2.model.ControlPlane;
import com.snets2.output.SimulationResult;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Collects general simulation metadata and request statistics.
 * 
 * <p>Tracks total simulated time, request durations (overall and per bit rate),
 * and the number of active requests over time (including average active requests).</p>
 */
public class SimulationMetadataMetrics {

    private static record HistoryPoint(double time, int value) {}

    private final List<HistoryPoint> history = new ArrayList<>();
    
    private double lastObservationTime = 0;
    private double totalObservationTime = 0;
    private double weightedActiveRequests = 0;
    
    private double totalDuration = 0;
    private long requestCount = 0;
    private final Map<Double, Double> durationPerBitRate = new HashMap<>();
    private final Map<Double, Long> countPerBitRate = new HashMap<>();

    /**
     * Records the duration and bit rate of a successfully established circuit.
     */
    public synchronized void recordRequestDuration(double duration, double bitRate) {
        totalDuration += duration;
        requestCount++;
        durationPerBitRate.merge(bitRate, duration, Double::sum);
        countPerBitRate.merge(bitRate, 1L, Long::sum);
    }

    /**
     * Records the state of active requests at a given time step.
     */
    public synchronized void recordObservation(ControlPlane cp, double currentTime) {
        double deltaT = currentTime - lastObservationTime;
        int activeCount = cp.getActiveCircuits().size();
        
        history.add(new HistoryPoint(currentTime, activeCount));
        
        if (deltaT > 0) {
            weightedActiveRequests += activeCount * deltaT;
            totalObservationTime += deltaT;
        }
        lastObservationTime = currentTime;
    }

    public synchronized void setLastObservationTime(double lastObservationTime) {
        this.lastObservationTime = lastObservationTime;
    }

    private synchronized double getAverageActiveRequests() {
        return totalObservationTime == 0 ? 0.0 : weightedActiveRequests / totalObservationTime;
    }

    private synchronized int getActiveRequestsAtTime(double targetT) {
        if (history.isEmpty()) return 0;
        int index = 0;
        while (index < history.size() && history.get(index).time <= targetT) {
            index++;
        }
        return index > 0 ? history.get(index - 1).value : history.get(0).value;
    }

    private Map<String, String> getDimensions(String metric, String bitrate, String timePercentage) {
        Map<String, String> dims = new java.util.TreeMap<>();
        dims.put("metric", metric);
        dims.put("bitrate", bitrate);
        dims.put("time_percentage", timePercentage);
        return dims;
    }

    /**
     * Populates the simulation results under the "SimulationMetadata" sheet.
     */
    public synchronized void fillResults(SimulationResult result, Map<String, Object> scenario, int repId, double totalSimTime) {
        String sheet = "SimulationMetadata";

        // 1. Total Simulated Time
        result.addValue(sheet, "Total Simulated Time", 
                        getDimensions("Total Simulated Time", "N/A", "N/A"), scenario, repId, totalSimTime);

        // 2. Average Request Duration
        double avgDuration = requestCount == 0 ? 0.0 : totalDuration / requestCount;
        result.addValue(sheet, "Average Request Duration", 
                        getDimensions("Average Request Duration", "all", "N/A"), scenario, repId, avgDuration);

        // 3. Average Request Duration per Bit Rate
        for (Double br : countPerBitRate.keySet()) {
            double avgBr = durationPerBitRate.get(br) / countPerBitRate.get(br);
            result.addValue(sheet, "Average Request Duration per Bit Rate", 
                            getDimensions("Average Request Duration per Bit Rate", String.format(java.util.Locale.US, "%.1f", br), "N/A"), scenario, repId, avgBr);
        }

        // 4. Average Active Requests
        result.addValue(sheet, "Average Active Requests", 
                        getDimensions("Average Active Requests", "N/A", "N/A"), scenario, repId, getAverageActiveRequests());

        // 5. Active Requests Over Time (sampled at 10% intervals)
        for (int i = 1; i <= 10; i++) {
            double targetTime = i * 0.1 * totalSimTime;
            int countAtTime = getActiveRequestsAtTime(targetTime);
            result.addValue(sheet, "Active Requests Over Time", 
                            getDimensions("Active Requests Over Time", "N/A", (i * 10) + "%"), scenario, repId, (double) countAtTime);
        }
    }
}
