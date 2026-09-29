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
            true, true, true, true, true, true, 
            0.07, 0.0, 80.0, 0.2, 1.3E-3, 1.6E-5, 1.93E14, 
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
        double ase = PhysicalLayerModel.calculateLinkAse(link1, config, 0.0);
        
        // With 100km link and 80km span: N_l = ceil(100/80 - 1) = 1 line amp, plus booster and pre-amp (20 km).
        // Power calculation must be positive and non-zero.
        assertTrue(ase > 0, "ASE noise density should be positive");
        
        // If we double the link length to 200km -> N_l = 2 line amps and a 40 km pre-amp segment.
        Link longLink = new Link("1", "2", 200.0, List.of(core0), List.of());
        double aseLong = PhysicalLayerModel.calculateLinkAse(longLink, config, 0.0);
        
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
        
        // Manually allocate in neighbor spectrum for test (or use ControlPlane)
        core1.getSpectrum().allocate(10, 15);
        
        int overlaps1 = PhysicalLayerModel.calculateTotalOverlaps(path, 0, 10, 20);
        // Core 0 slots 10-15 see neighbor on Core 1. That's 6 slots overlapping.
        assertEquals(6, overlaps1);
    }

    /** Same parameters as {@link #setUp()} with the amplifier/PSD keys overridable. */
    private static PhysicalLayerConfig config(int typeOfAmplifierGain, double a1, double switchInsertionLoss, boolean fixedPsd) {
        return new PhysicalLayerConfig(
            true, true, true, true, true, true,
            0.07, 0.0, 80.0, 0.2, 1.3E-3, 1.6E-5, 1.93E14,
            6.626E-34, 5.0, 16.0, a1, 4.0, typeOfAmplifierGain, 1.93E14, switchInsertionLoss,
            fixedPsd, 1.25E10, 1.0E7, 0.01, 0.012, 4.5E-5, 2.0, 1, 12.5E9
        );
    }

    @Test
    @DisplayName("Line amplifier count follows ceil(L/Lspan - 1)")
    void testNumberOfLineAmplifiers() {
        assertEquals(0, PhysicalLayerModel.numberOfLineAmplifiers(50.0, 80.0));
        assertEquals(0, PhysicalLayerModel.numberOfLineAmplifiers(80.0, 80.0));
        assertEquals(1, PhysicalLayerModel.numberOfLineAmplifiers(100.0, 80.0));
        assertEquals(1, PhysicalLayerModel.numberOfLineAmplifiers(160.0, 80.0));
        assertEquals(2, PhysicalLayerModel.numberOfLineAmplifiers(200.0, 80.0));
    }

    @Test
    @DisplayName("Fixed-gain amplifier ASE = NF h nu (G - 1)")
    void testFixedGainAmplifierAse() {
        double g0 = Math.pow(10, 1.6);
        assertEquals(g0, PhysicalLayerModel.amplifierGain(config, g0, 1.0), "Fixed gain must ignore the load");
        double expected = Math.pow(10, 0.5) * 6.626E-34 * 1.93E14 * (g0 - 1.0);
        assertEquals(expected, PhysicalLayerModel.amplifierAse(config, g0, 1.0), expected * 1E-12);
    }

    @Test
    @DisplayName("switchInsertionLoss sets the booster gain (3 x Lsss)")
    void testSwitchInsertionLoss() {
        double aseLowLoss = PhysicalLayerModel.calculateLinkAse(link1, config(0, 100.0, 3.0, false), 0.0);
        double aseHighLoss = PhysicalLayerModel.calculateLinkAse(link1, config(0, 100.0, 6.0, false), 0.0);
        assertTrue(aseHighLoss > aseLowLoss, "Higher ROADM loss requires more booster gain and more ASE");

        double expected = PhysicalLayerModel.amplifierAse(config, Math.pow(10, 1.8), 0.0)
                        - PhysicalLayerModel.amplifierAse(config, Math.pow(10, 0.9), 0.0);
        assertEquals(expected, aseHighLoss - aseLowLoss, expected * 1E-9);
    }

    @Test
    @DisplayName("Saturated gain: G = G0 / (1 + G0 Pin / Psat), equal to G0 at low input power")
    void testSaturatedGain() {
        PhysicalLayerConfig saturated = config(PhysicalLayerConfig.AMP_GAIN_SATURATED, 100.0, 5.0, false);
        double g0 = Math.pow(10, 1.6);
        double pSat = PhysicalLayerModel.dbmToWatts(16.0);

        assertEquals(g0, PhysicalLayerModel.amplifierGain(saturated, g0, 1E-15), g0 * 1E-9);
        assertEquals(g0 / 2.0, PhysicalLayerModel.amplifierGain(saturated, g0, pSat / g0), 1E-9);
        assertEquals(1.0, PhysicalLayerModel.amplifierGain(saturated, g0, 1E3), "Gain is clamped at 1");
    }

    @Test
    @DisplayName("Noise factor model (A1, A2) raises the noise factor with the input power")
    void testNoiseFactorModel() {
        PhysicalLayerConfig withoutPenalty = config(PhysicalLayerConfig.AMP_GAIN_SATURATED, 0.0, 5.0, false);
        PhysicalLayerConfig withPenalty = config(PhysicalLayerConfig.AMP_GAIN_SATURATED, 100.0, 5.0, false);
        double g = 20.0;
        double pIn = 1E-3;

        double ratio = PhysicalLayerModel.amplifierAse(withPenalty, g, pIn)
                     / PhysicalLayerModel.amplifierAse(withoutPenalty, g, pIn);
        assertEquals(1.0 + 100.0 - 100.0 / (1.0 + pIn / 4.0), ratio, 1E-9);
        // Fixed gain ignores A1/A2
        assertEquals(PhysicalLayerModel.amplifierAse(config, g, pIn), PhysicalLayerModel.amplifierAse(config, g, 0.0));
    }

    @Test
    @DisplayName("Saturated gain: link ASE (referred to the nominal signal) grows with the load")
    void testSaturatedLinkAseGrowsWithLoad() {
        PhysicalLayerConfig saturated = config(PhysicalLayerConfig.AMP_GAIN_SATURATED, 0.0, 5.0, false);
        Link longLink = new Link("1", "2", 800.0, List.of(core0), List.of());

        double unloaded = PhysicalLayerModel.calculateLinkAse(longLink, saturated, 1E-12);
        double fixed = PhysicalLayerModel.calculateLinkAse(longLink, config, 0.0);
        assertEquals(fixed, unloaded, fixed * 1E-6, "Negligible load behaves as fixed gain");

        double light = PhysicalLayerModel.calculateLinkAse(longLink, saturated, 0.01);
        double heavy = PhysicalLayerModel.calculateLinkAse(longLink, saturated, 0.05);
        assertTrue(light > unloaded, "Compressed gain lowers the signal along the cascade");
        assertTrue(heavy > light, "More load, more compression, lower SNR");
    }

    @Test
    @DisplayName("Fixed PSD: launch power scales with bandwidth and PSD equals P_ref/B_ref")
    void testFixedPowerSpectralDensity() {
        PhysicalLayerConfig fixedPsd = config(0, 100.0, 5.0, true);
        double pRef = PhysicalLayerModel.dbmToWatts(0.0);

        double p100 = PhysicalLayerModel.circuitLaunchPower(fixedPsd, qpsk, 100.0);
        double p200 = PhysicalLayerModel.circuitLaunchPower(fixedPsd, qpsk, 200.0);
        assertEquals(2.0 * p100, p200, p100 * 1E-12);
        assertEquals(pRef / 1.25E10, PhysicalLayerModel.signalPsd(fixedPsd, qpsk, 100.0), 1E-20);
        assertEquals(pRef / 1.25E10, PhysicalLayerModel.signalPsd(fixedPsd, qpsk, 200.0), 1E-20);

        // Variable PSD: every circuit uses 'power'
        assertEquals(pRef, PhysicalLayerModel.circuitLaunchPower(config, qpsk, 100.0), 1E-15);
        assertEquals(pRef, PhysicalLayerModel.circuitLaunchPower(config, qpsk, 200.0), 1E-15);
    }

    @Test
    @DisplayName("Saturated gain: SNR prediction depends on the core load")
    void testPredictSnrWithSaturatedGain() {
        PhysicalLayerConfig saturated = config(PhysicalLayerConfig.AMP_GAIN_SATURATED, 100.0, 5.0, false);
        ControlPlane cp = new ControlPlane(topology, null, 12.5E9, 1, saturated);
        Path path = new Path(List.of(link1));

        double snrEmpty = PhysicalLayerModel.predictSNR(cp, path, 0, 10, 20, qpsk, 100.0);
        core0.addLaunchPower(0.05);
        double snrLoaded = PhysicalLayerModel.predictSNR(cp, path, 0, 10, 20, qpsk, 100.0);
        assertNotEquals(snrEmpty, snrLoaded, "Saturated amplifiers must react to the core load");

        // Fixed gain ignores the load
        ControlPlane cpFixed = new ControlPlane(topology, null, 12.5E9, 1, config);
        double a = PhysicalLayerModel.predictSNR(cpFixed, path, 0, 10, 20, qpsk, 100.0);
        core0.removeLaunchPower(0.05);
        double b = PhysicalLayerModel.predictSNR(cpFixed, path, 0, 10, 20, qpsk, 100.0);
        assertEquals(a, b, a * 1E-12);
    }

    @Test
    @DisplayName("Invalid typeOfAmplifierGain is rejected")
    void testInvalidAmplifierGainType() {
        assertThrows(IllegalArgumentException.class, () -> config(2, 100.0, 5.0, false));
    }
}
