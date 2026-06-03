package com.snets2.engine;

import com.snets2.model.AllocationSolution;
import com.snets2.model.ControlPlane;
import com.snets2.model.Core;
import com.snets2.model.Link;
import com.snets2.model.ModulationFormat;
import com.snets2.model.NetworkTopology;
import com.snets2.model.Node;
import com.snets2.rmsca.IRMSCA;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SimulationEngineTest {

    @Test
    void testSimulationLoopLifecycle() {
        // 1. Setup Network Topology
        Node n1 = new Node("1", 100, 100, 0);
        Node n2 = new Node("2", 100, 100, 0);
        Core core = new Core(0, List.of(), 100);
        Link link = new Link("1", "2", 100.0, List.of(core), List.of());
        
        ModulationFormat m1 = new ModulationFormat("BPSK", 4000.0, 2.0, 6.0, -20.0, 32.0, 0.1);
        NetworkTopology topology = new NetworkTopology(List.of(n1, n2), List.of(link), List.of(m1));

        // 2. Mock RMSCA: Always allocates 10 slots on the only core/link
        IRMSCA mockRmsca = (controlPlane, source, destination, bitRate) -> {
            if (core.getSpectrum().isRangeFree(0, 9)) {
                return new AllocationSolution(source, destination, List.of(link), List.of(0), 0, 9, m1, bitRate);
            }
            return null;
        };

        // 3. Initialize Control Plane (with RMSCA and slotBandwidth)
        ControlPlane cp = new ControlPlane(topology, mockRmsca, 12.5E9, 1, null);

        // 4. Initialize Engine (with Topology and Control Plane)
        SimulationEngine engine = new SimulationEngine(topology, cp, 10, 1.0, List.of(), 42L);
        
        // 5. Schedule first arrival
        engine.schedule(new ArrivalEvent(0.0, n1, n2, 100.0));

        // 6. Run simulation
        engine.run();

        // 7. Verify results
        assertTrue(engine.getArrivalCounter() >= 10);
        System.out.println("Final Time: " + engine.getCurrentTime());
        System.out.println("Arrivals processed: " + engine.getArrivalCounter());
    }
}
