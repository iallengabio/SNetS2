package com.snets2.metrics;

import com.snets2.model.Circuit;
import com.snets2.model.Link;
import com.snets2.output.SimulationResult;
import java.util.HashMap;
import java.util.Map;

/**
2:  * Tracks allocation slot size distributions generally and per link.
3:  */
public class SpectrumSizeMetrics {
    private long totalRequests = 0;
    private final Map<Integer, Long> numberReqPerSlot = new HashMap<>();

    private final Map<String, Long> totalRequestsPerLink = new HashMap<>();
    private final Map<String, Map<Integer, Long>> numberReqPerSlotPerLink = new HashMap<>();

    public void recordCircuitSetup(Circuit circuit) {
        int slots = circuit.getEndSlot() - circuit.getStartSlot() + 1;
        
        totalRequests++;
        numberReqPerSlot.merge(slots, 1L, Long::sum);

        for (Link link : circuit.getPath()) {
            String linkId = link.getSourceId() + "->" + link.getDestinationId();
            totalRequestsPerLink.merge(linkId, 1L, Long::sum);
            numberReqPerSlotPerLink.computeIfAbsent(linkId, k -> new HashMap<>())
                                  .merge(slots, 1L, Long::sum);
        }
    }

    public void fillResults(SimulationResult result, Map<String, Object> scenario, int repId) {
        String sheet = "SpectrumSizeStatistics";
        if (totalRequests == 0) return;

        for (Integer slots : numberReqPerSlot.keySet()) {
            double percent = (double) numberReqPerSlot.get(slots) / totalRequests;
            result.addValue(sheet, "Percentage per Slot Size", Map.of("slots", String.valueOf(slots), "link", "all"), scenario, repId, percent);
        }

        for (String linkId : numberReqPerSlotPerLink.keySet()) {
            long linkTotal = totalRequestsPerLink.get(linkId);
            if (linkTotal == 0) continue;
            Map<Integer, Long> slotMap = numberReqPerSlotPerLink.get(linkId);
            for (Integer slots : slotMap.keySet()) {
                double percent = (double) slotMap.get(slots) / linkTotal;
                result.addValue(sheet, "Percentage per Slot Size per Link", 
                    Map.of("slots", String.valueOf(slots), "link", linkId), scenario, repId, percent);
            }
        }
    }
}
