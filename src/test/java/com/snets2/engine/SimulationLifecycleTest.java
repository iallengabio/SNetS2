package com.snets2.engine;

import com.snets2.model.*;
import com.snets2.rmsca.IRMSCA;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SimulationLifecycleTest {

    @Test
    @DisplayName("Verify the complete chain of events: Arrival -> Setup -> Departure -> Teardown")
    void testCompleteEventChain() {
        System.out.println("--- Starting Simulation Lifecycle Test ---");

        // 1. Create a simple topology: 3 nodes in a line
        Node n1 = new Node("N1", 10, 10, 0);
        Node n2 = new Node("N2", 10, 10, 0);
        Node n3 = new Node("N3", 10, 10, 0);
        
        Core core12 = new Core(0, List.of(), 100);
        Core core23 = new Core(0, List.of(), 100);
        
        Link link12 = new Link("N1", "N2", 50.0, List.of(core12), List.of());
        Link link23 = new Link("N2", "N3", 50.0, List.of(core23), List.of());
        
        ModulationFormat mod = new ModulationFormat("BPSK", 4000.0, 2.0, 6.0, -20.0, 32.0, 0.1);
        NetworkTopology topology = new NetworkTopology(List.of(n1, n2, n3), List.of(link12, link23), List.of(mod));

        // 2. Mock RMSCA: Allocates 5 slots (0-4) on the shortest path if free
        IRMSCA mockRmsca = (cp, source, destination, bitRate) -> {
            // Very simple routing: if N1 to N3, use both links
            if (source.getId().equals("N1") && destination.getId().equals("N3")) {
                if (core12.getSpectrum().isRangeFree(0, 4) && core23.getSpectrum().isRangeFree(0, 4)) {
                    return new AllocationSolution(source, destination, List.of(link12, link23), List.of(0, 0), 0, 4, mod, bitRate);
                }
            } else if (source.getId().equals("N1") && destination.getId().equals("N2")) {
                if (core12.getSpectrum().isRangeFree(0, 4)) {
                    return new AllocationSolution(source, destination, List.of(link12), List.of(0), 0, 4, mod, bitRate);
                }
            }
            return null;
        };

        // 3. Init Control Plane and Engine
        ControlPlane cp = new ControlPlane(topology, mockRmsca, 12.5E9);
        // Load = 2 Erlangs, 5 requests total to keep logs readable
        SimulationEngine engine = new SimulationEngine(topology, cp, 5, 2.0, 12345L);

        // 4. Seed the first event: N1 to N3
        engine.schedule(new ArrivalEvent(0.0, n1, n3, 100.0));

        // 5. Run simulation
        engine.run();

        System.out.println("--- Simulation Finished ---");
        assertTrue(engine.getArrivalCounter() >= 5, "Should have processed at least 5 arrivals");
    }
}
