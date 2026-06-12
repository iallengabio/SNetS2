package com.snets2.metrics;

import com.snets2.model.*;
import com.snets2.output.SimulationResult;
import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class SimulationMetadataMetricsTest {

    @Test
    void testSimulationMetadataMetricsRecording() {
        SimulationMetadataMetrics metrics = new SimulationMetadataMetrics();

        // 1. Record request durations
        metrics.recordRequestDuration(10.0, 100.0);
        metrics.recordRequestDuration(20.0, 100.0);
        metrics.recordRequestDuration(30.0, 200.0);

        // 2. Mock ControlPlane for active requests observations
        Node n1 = new Node("1", 10, 10, 0);
        Node n2 = new Node("2", 10, 10, 0);
        Core core = new Core(0, List.of(), 100);
        Link link = new Link("1", "2", 100.0, List.of(core), List.of());
        ModulationFormat mod = new ModulationFormat("BPSK", 4000.0, 2.0, 6.0, -20.0, 32.0, 0.1);
        NetworkTopology topology = new NetworkTopology(List.of(n1, n2), List.of(link), List.of(mod));
        ControlPlane cp = new ControlPlane(topology, null, 12.5E9, 1, null);

        // Record observations
        metrics.recordObservation(cp, 0.0); // 0 active

        // Add an active circuit
        Circuit c1 = new Circuit("c1", n1, n2, List.of(link), List.of(0), 0, 4, mod, 100.0, List.of());
        cp.establishCircuit(c1);
        metrics.recordObservation(cp, 10.0); // 1 active from t=0 to t=10 (delta=10)

        // Add another active circuit
        Circuit c2 = new Circuit("c2", n1, n2, List.of(link), List.of(0), 5, 9, mod, 200.0, List.of());
        cp.establishCircuit(c2);
        metrics.recordObservation(cp, 20.0); // 2 active from t=10 to t=20 (delta=10)

        // Teardown c1
        cp.teardownCircuit("c1");
        metrics.recordObservation(cp, 30.0); // 1 active from t=20 to t=30 (delta=10)

        // Fill results
        SimulationResult result = new SimulationResult(1);
        Map<String, Object> scenario = new HashMap<>();
        scenario.put("load", 1.0);

        metrics.fillResults(result, scenario, 0, 30.0);

        // Verify sheet content
        Map<String, Map<String, SimulationResult.MetricRow>> data = result.getData();
        assertTrue(data.containsKey("SimulationMetadata"));
        Map<String, SimulationResult.MetricRow> sheetRows = data.get("SimulationMetadata");

        // Verify total simulated time
        String timeKey = scenario.toString() + "_Total Simulated Time_" + Map.of("metric", "value").toString();
        assertTrue(sheetRows.containsKey(timeKey));
        assertEquals(30.0, sheetRows.get(timeKey).getRepValues().get(0));

        // Verify average request duration: (10 + 20 + 30) / 3 = 20.0
        String avgDurKey = scenario.toString() + "_Average Request Duration_" + Map.of("bitrate", "all").toString();
        assertTrue(sheetRows.containsKey(avgDurKey));
        assertEquals(20.0, sheetRows.get(avgDurKey).getRepValues().get(0));

        // Verify duration by bit rate: 100.0 has (10 + 20) / 2 = 15.0; 200.0 has 30.0
        String avgDurBr100Key = scenario.toString() + "_Average Request Duration per Bit Rate_" + Map.of("bitrate", "100.0").toString();
        String avgDurBr200Key = scenario.toString() + "_Average Request Duration per Bit Rate_" + Map.of("bitrate", "200.0").toString();
        assertTrue(sheetRows.containsKey(avgDurBr100Key));
        assertEquals(15.0, sheetRows.get(avgDurBr100Key).getRepValues().get(0));
        assertTrue(sheetRows.containsKey(avgDurBr200Key));
        assertEquals(30.0, sheetRows.get(avgDurBr200Key).getRepValues().get(0));

        // Verify average active requests: (1*10 + 2*10 + 1*10) / 30 = 40 / 30 = 1.3333333333333333
        String avgActiveKey = scenario.toString() + "_Average Active Requests_" + Map.of("metric", "value").toString();
        assertTrue(sheetRows.containsKey(avgActiveKey));
        assertEquals(40.0 / 30.0, sheetRows.get(avgActiveKey).getRepValues().get(0), 1E-9);

        // Verify active requests over time at 10% (t=3.0) -> index before t=3 is t=0 which had 0
        String active10PercentKey = scenario.toString() + "_Active Requests Over Time_" + Map.of("time_percentage", "10%").toString();
        assertTrue(sheetRows.containsKey(active10PercentKey));
        assertEquals(0.0, sheetRows.get(active10PercentKey).getRepValues().get(0));

        // Verify at 50% (t=15.0) -> index before t=15 is t=10 which had 1
        String active50PercentKey = scenario.toString() + "_Active Requests Over Time_" + Map.of("time_percentage", "50%").toString();
        assertTrue(sheetRows.containsKey(active50PercentKey));
        assertEquals(1.0, sheetRows.get(active50PercentKey).getRepValues().get(0));

        // Verify at 100% (t=30.0) -> t=30.0 has 1
        String active100PercentKey = scenario.toString() + "_Active Requests Over Time_" + Map.of("time_percentage", "100%").toString();
        assertTrue(sheetRows.containsKey(active100PercentKey));
        assertEquals(1.0, sheetRows.get(active100PercentKey).getRepValues().get(0));
    }
}
