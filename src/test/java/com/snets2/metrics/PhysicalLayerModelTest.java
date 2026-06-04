package com.snets2.metrics;

import com.snets2.config.PhysicalLayerConfig;
import com.snets2.model.*;
import com.snets2.rmsca.routing.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PhysicalLayerModelTest {

    private PhysicalLayerConfig config;
    private NetworkTopology topology;
    private Link link1;
    private Core core0, core1;
    private ModulationFormat qpsk;

    @BeforeEach
    void setUp() {
        // Standard config for testing
        config = new PhysicalLayerConfig(
            1, 0, true, true, true, true, true, true, 
            0.07, 0, 0.0, 80.0, 0.2, 1.3E-3, 1.6E-5, 1.93E14, 
            6.626E-34, 5.0, 16.0, 100.0, 4.0, 0, 1.93E14, 5.0, 
            false, 1.25E10, 1.0E7, 0.01, 0.012, 4.5E-5, 2.0, 1, 12.5E9
        );

        Node n1 = new Node("1", 10, 10, 0);
        Node n2 = new Node("2", 10, 10, 0);
        
        // Adjacency: 0 and 1 are neighbors
        core0 = new Core(0, List.of(1), 320);
        core1 = new Core(1, List.of(0), 320);
        
        link1 = new Link("1", "2", 100.0, List.of(core0, core1), List.of(new Amplifier("a1", 16, 5, 100, 16)));
        qpsk = new ModulationFormat("QPSK", 2000.0, 4.0, 12.0, -25.0, 32.0, 0.1);
        topology = new NetworkTopology(List.of(n1, n2), List.of(link1), List.of(qpsk));
    }

    @Test
    @DisplayName("Verify ASE noise calculation logic")
    void testCalculateLinkAse() {
        double ase = PhysicalLayerModel.calculateLinkAse(link1, config, 12.5E9);
        
        // With 100km link and 80km span, we have floor(100/80) = 1 line amp.
        // Total amps = 2 (booster/pre) + 1 (line) = 3.
        // Power calculation must be positive and non-zero.
        assertTrue(ase > 0, "ASE noise density should be positive");
        
        // If we double the link length to 200km -> floor(200/80) = 2 line amps.
        // Total = 4 amps.
        Link longLink = new Link("1", "2", 200.0, List.of(core0), List.of());
        double aseLong = PhysicalLayerModel.calculateLinkAse(longLink, config, 12.5E9);
        
        assertTrue(aseLong > ase, "Longer link should have more ASE noise");
    }

    @Test
    @DisplayName("Verify Crosstalk (XT) contribution calculation")
    void testCalculateXtContribution() {
        Circuit circuit = new Circuit("c1", topology.nodes().get(0), topology.nodes().get(1), 
                                     List.of(link1), List.of(0), 10, 20, qpsk, 100.0);
        
        double xtNoise = PhysicalLayerModel.calculateXtContribution(link1, config, circuit);
        
        assertTrue(xtNoise > 0, "XT noise contribution should be positive");
        
        // XT depends linearly on length L in Lobato model.
        Link longLink = new Link("1", "2", 200.0, List.of(core0), List.of());
        double xtNoiseLong = PhysicalLayerModel.calculateXtContribution(longLink, config, circuit);
        
        assertEquals(2.0 * xtNoise, xtNoiseLong, 1E-10, "XT should double if link length doubles (Lobato)");
    }

    @Test
    @DisplayName("Verify NLI mask generation with frequency decay")
    void testGenerateNliMask() {
        Circuit circuit = new Circuit("c1", topology.nodes().get(0), topology.nodes().get(1), 
                                     List.of(link1), List.of(0), 50, 60, qpsk, 100.0);
        
        double[] mask = PhysicalLayerModel.generateNliMask(link1, config, circuit, 320);
        
        int center = 55;
        assertTrue(mask[center] > 0, "Noise at center should be positive");
        
        // Noise should decay as we move away from center frequency
        assertTrue(mask[center] > mask[center + 10], "NLI should decay with frequency distance");
        assertTrue(mask[center + 10] > mask[center + 50], "NLI should continue decaying");
    }

    @Test
    @DisplayName("Verify SNR prediction based on cache")
    void testPredictSNR() {
        ControlPlane cp = new ControlPlane(topology, null, 12.5E9, 1, config);
        Path path = new Path(List.of(link1));
        
        // Initial SNR with only ASE (caches are empty)
        double snrInitial = PhysicalLayerModel.predictSNR(cp, path, 0, 10, 20, qpsk, 100.0);
        
        // Add noise manually to cache
        core0.addNliNoise(15, 1E-15);
        
        double snrAfterNoise = PhysicalLayerModel.predictSNR(cp, path, 0, 10, 20, qpsk, 100.0);
        
        assertTrue(snrAfterNoise < snrInitial, "SNR should decrease when noise is added to cache");
    }

    @Test
    @DisplayName("Verify Overlap counting logic")
    void testCalculateTotalOverlaps() {
        Path path = new Path(List.of(link1));
        
        // No overlaps initially
        int overlaps0 = PhysicalLayerModel.calculateTotalOverlaps(path, 0, 10, 20);
        assertEquals(0, overlaps0);
        
        // Establish a circuit on adjacent core 1, overlapping slots 10-15
        Circuit neighbor = new Circuit("c_neighbor", topology.nodes().get(0), topology.nodes().get(1), 
                                       List.of(link1), List.of(1), 10, 15, qpsk, 100.0);
        
        // Manually allocate in neighbor spectrum for test (or use ControlPlane)
        core1.getSpectrum().allocate(10, 15);
        
        int overlaps1 = PhysicalLayerModel.calculateTotalOverlaps(path, 0, 10, 20);
        // Core 0 slots 10-15 see neighbor on Core 1. That's 6 slots overlapping.
        assertEquals(6, overlaps1);
    }
}
