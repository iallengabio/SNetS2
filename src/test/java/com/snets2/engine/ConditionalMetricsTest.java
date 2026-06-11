package com.snets2.engine;

import com.snets2.model.*;
import com.snets2.rmsca.IRMSCA;
import com.snets2.metrics.BitRateBlockingMetrics;
import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ConditionalMetricsTest {

    @Test
    void testDisabledMetricsAreNotRecorded() {
        Node n1 = new Node("N1", 10, 10, 0);
        Node n2 = new Node("N2", 10, 10, 0);
        Core core = new Core(0, List.of(), 100);
        Link link = new Link("N1", "N2", 100.0, List.of(core), List.of());
        ModulationFormat mod = new ModulationFormat("BPSK", 4000.0, 2.0, 6.0, -20.0, 32.0, 0.1);
        NetworkTopology topology = new NetworkTopology(List.of(n1, n2), List.of(link), List.of(mod));

        IRMSCA mockRmsca = (cp, source, destination, bitRate) -> {
            if (core.getSpectrum().isRangeFree(0, 4)) {
                return new AllocationSolution(source, destination, List.of(link), List.of(0), 0, 4, mod, bitRate);
            }
            return null;
        };

        ControlPlane cp = new ControlPlane(topology, mockRmsca, 12.5E9, 1, null);

        // Disable BlockingProbability, BitRateBlockingProbability & SpectrumUtilization
        Map<String, Boolean> activeMetrics = new HashMap<>();
        activeMetrics.put("BlockingProbability", false);
        activeMetrics.put("BitRateBlockingProbability", false);
        activeMetrics.put("SpectrumUtilization", false);

        SimulationEngine engine = new SimulationEngine(topology, cp, 5, 0, activeMetrics, 1.0, List.of(), 42L);

        // Verify active metric statuses
        assertFalse(engine.isActiveMetric("BlockingProbability"));
        assertFalse(engine.isActiveMetric("BitRateBlockingProbability"));
        assertFalse(engine.isActiveMetric("SpectrumUtilization"));
        assertTrue(engine.isActiveMetric("ModulationUtilization")); // Defaults to true when missing

        // Schedule arrival and execute
        engine.schedule(new ArrivalEvent(0.0, n1, n2, 100.0));
        engine.run();

        // Check if BitRateBlockingMetrics remains completely empty (0 requested bit rate)
        BitRateBlockingMetrics blockingMetrics = engine.getMetricsManager().getBitRateBlocking();
        assertEquals(0.0, blockingMetrics.getGeneralRequestedBitRate(), "Disabled metric should not record any arrivals.");
    }
}
