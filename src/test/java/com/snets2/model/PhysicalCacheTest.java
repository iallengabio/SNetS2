package com.snets2.model;

import com.snets2.config.PhysicalLayerConfig;
import com.snets2.rmsca.IRMSCA;
import com.snets2.rmsca.routing.Path;
import com.snets2.rmsca.spectrum.SpectrumInterval;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PhysicalCacheTest {

    private ControlPlane cp;
    private Node nodeA, nodeB;
    private Link linkAB;
    private Core core0, core1;
    private ModulationFormat qpsk;
    private PhysicalLayerConfig config;

    @BeforeEach
    void setUp() {
        nodeA = new Node("A", 10, 10, 0);
        nodeB = new Node("B", 10, 10, 0);
        
        // Adjacency: 0 is neighbor of 1
        core0 = new Core(0, List.of(1), 100);
        core1 = new Core(1, List.of(0), 100);
        
        linkAB = new Link("A", "B", 100.0, List.of(core0, core1), List.of());
        qpsk = new ModulationFormat("QPSK", 2000.0, 4.0, 12.0, -25.0, 32.0, 0.1);
        NetworkTopology topology = new NetworkTopology(List.of(nodeA, nodeB), List.of(linkAB), List.of(qpsk));
        
        config = new PhysicalLayerConfig(
            0, 0, true, true, true, true, true, true, 
            0.07, 0, 0.0, 80.0, 0.2, 1.3E-3, 1.6E-5, 1.93E14, 
            6.626E-34, 5.0, 16.0, 100.0, 4.0, 0, 1.93E14, 5.0, 
            false, 1.25E10, 1.0E7, 0.01, 0.012, 4.5E-5, 2.0, 1, 12.5E9
        );

        cp = new ControlPlane(topology, null, 12.5E9, 1, config);
    }

    @Test
    void testCacheMutationAndConsistency() {
        Circuit circuit = new Circuit("c1", nodeA, nodeB, List.of(linkAB), List.of(0), 10, 20, qpsk, 100.0);
        
        // 1. Initial state: 0 noise (except static ASE which is in Link)
        assertEquals(0.0, core0.getAverageNliNoise(10, 20));
        assertEquals(0.0, core1.getAverageXtNoise(10, 20));

        // 2. Establish circuit on Core 0
        cp.establishCircuit(circuit);
        
        // NLI on Core 0 should be > 0
        assertTrue(core0.getAverageNliNoise(10, 20) > 0);
        // XT on Core 1 (adjacent) should be > 0
        assertTrue(core1.getAverageXtNoise(10, 20) > 0);
        
        // 3. Teardown
        cp.teardownCircuit("c1");
        
        // Caches must return to 0 (with small epsilon for double precision)
        assertEquals(0.0, core0.getAverageNliNoise(10, 20), 1E-20);
        assertEquals(0.0, core1.getAverageXtNoise(10, 20), 1E-20);
    }
}
