package com.snets2.verification;

import com.snets2.config.PhysicalLayerConfig;
import com.snets2.metrics.BlockingCause;
import com.snets2.metrics.PhysicalLayerModel;
import com.snets2.model.*;
import com.snets2.rmsca.StandardIntegratedRMSCA;
import com.snets2.rmsca.core.FirstFitCoreAssignment;
import com.snets2.rmsca.modulation.DistanceAdaptiveModulationSelection;
import com.snets2.rmsca.modulation.FixedModulationSelection;
import com.snets2.rmsca.regenerator.AsSoonAsRequiredRegeneratorAssignment;
import com.snets2.rmsca.routing.DijkstraRouting;
import com.snets2.rmsca.routing.Path;
import com.snets2.rmsca.spectrum.FirstFitSpectrumAssignment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Crosstalk magnitude/threshold and regenerator flow of the integrated RMSCA
 * (verification levels L9-e, L9-f, L9-j and L3-e of docs/review/03_plano_de_verificacao.md;
 * regression tests for CR-05, CR-06 and CR-07).
 */
class RmscaPhysicalConstraintsTest {

    private static final double SLOT = 12.5E9;

    /** h = 2 k^2 R / (beta Lambda) with the experiment01 fiber parameters: 6.4e-9 1/m. */
    private static final double H = 2 * 0.012 * 0.012 * 0.01 / (1.0E7 * 4.5E-5);

    private static PhysicalLayerConfig config(boolean qot, boolean xt) {
        // NLI off to isolate ASE + XT; guard band 0
        return new PhysicalLayerConfig(
            0, 0, qot, qot, true, false, xt, xt,
            0.0, 0, 0.0, 80.0, 0.2, 0.0013, 1.6E-5, 1.9385E14,
            6.626E-34, 5.0, 16.0, 100.0, 4.0, 0, 1.9385E14, 5.0,
            false, 1.25E10, 1.0E7, 0.01, 0.012, 4.5E-5, 1.0, 0, SLOT);
    }

    private static ModulationFormat mod(String name, double maxReach, double m, double xtThresholdDb) {
        return new ModulationFormat(name, maxReach, m, 5.0, xtThresholdDb, 32, 0.1);
    }

    /** Chain of nodes 0..n with one directed link i -> i+1 per hop, all cores mutually adjacent. */
    private static ControlPlane chain(int hops, double hopKm, int cores, int slots, int regenerators,
                                      List<ModulationFormat> mods, PhysicalLayerConfig cfg, StandardIntegratedRMSCA rmsca) {
        List<Node> nodes = new ArrayList<>();
        for (int i = 0; i <= hops; i++) nodes.add(new Node(String.valueOf(i), 10, 10, regenerators));
        List<Link> links = new ArrayList<>();
        for (int i = 0; i < hops; i++) {
            List<Core> cs = new ArrayList<>();
            for (int c = 0; c < cores; c++) {
                List<Integer> adj = new ArrayList<>();
                for (int d = 0; d < cores; d++) if (d != c) adj.add(d);
                cs.add(new Core(c, adj, slots));
            }
            links.add(new Link(String.valueOf(i), String.valueOf(i + 1), hopKm, cs, List.of()));
        }
        return new ControlPlane(new NetworkTopology(nodes, links, mods), rmsca, SLOT, 0, cfg);
    }

    private static StandardIntegratedRMSCA rmsca() {
        StandardIntegratedRMSCA r = new StandardIntegratedRMSCA();
        r.setRouting(new DijkstraRouting());
        r.setCoreAssignment(new FirstFitCoreAssignment());
        r.setSpectrumAssignment(new FirstFitSpectrumAssignment());
        return r;
    }

    private static Circuit circuit(ControlPlane cp, String id, int fromLink, int toLink, int core, int s, int e, ModulationFormat m) {
        List<Link> path = cp.getLinks().subList(fromLink, toLink + 1);
        List<Integer> cores = new ArrayList<>();
        for (int i = 0; i < path.size(); i++) cores.add(core);
        return new Circuit(id, cp.getNode(path.get(0).getSourceId()), cp.getNode(path.get(path.size() - 1).getDestinationId()),
                path, cores, s, e, m, 100.0);
    }

    @Test
    @DisplayName("L9-e: XT ratio = n * overlap fraction * h * L, reported in dB comparable with the thresholds")
    void crosstalkMagnitude() {
        ModulationFormat m = mod("M", 5000, 4, -20);
        ControlPlane cp = chain(1, 100.0, 3, 320, 0, List.of(m), config(true, true), rmsca());
        Path path = new Path(cp.getLinks());
        double hl = H * 100e3;

        assertEquals(0.0, PhysicalLayerModel.predictXtRatio(cp, path, 0, 100, 103), 0.0);

        cp.establishCircuit(circuit(cp, "full", 0, 0, 1, 100, 103, m));      // full overlap, same bandwidth
        assertEquals(hl, PhysicalLayerModel.predictXtRatio(cp, path, 0, 100, 103), hl * 1E-9);
        assertEquals(10 * Math.log10(hl), PhysicalLayerModel.predictXT(cp, path, List.of(), 0, 100, 103), 1E-9);
        assertEquals(-31.94, PhysicalLayerModel.predictXT(cp, path, List.of(), 0, 100, 103), 0.01);

        cp.establishCircuit(circuit(cp, "half", 0, 0, 2, 102, 105, m));      // second neighbour, 2 of 4 slots overlap
        assertEquals(1.5 * hl, PhysicalLayerModel.predictXtRatio(cp, path, 0, 100, 103), hl * 1E-9);

        // XT accumulates linearly along a path
        ControlPlane cp2 = chain(2, 100.0, 2, 320, 0, List.of(m), config(true, true), rmsca());
        cp2.establishCircuit(circuit(cp2, "n", 0, 1, 1, 10, 13, m));
        assertEquals(2 * hl, PhysicalLayerModel.predictXtRatio(cp2, new Path(cp2.getLinks()), 0, 10, 13), hl * 1E-9);
    }

    @Test
    @DisplayName("L9-f: the XT threshold of the modulation format is enforced (CROSSTALK)")
    void crosstalkThresholdIsEnforced() {
        // Only core 0 slots 0..3 are free; cores 1 and 2 fully overlap it: XT = 2hL = -28.9 dB on 100 km.
        for (double thresholdDb : new double[] {-30.0, -28.0}) {
            ModulationFormat m = mod("M", 5000, 4, thresholdDb);
            StandardIntegratedRMSCA r = rmsca();
            r.setModulationSelection(new FixedModulationSelection());
            ControlPlane cp = chain(1, 100.0, 3, 4, 0, List.of(m), config(true, true), r);
            cp.establishCircuit(circuit(cp, "a", 0, 0, 1, 0, 3, mod("lenient", 5000, 4, 0)));
            cp.establishCircuit(circuit(cp, "b", 0, 0, 2, 0, 3, mod("lenient", 5000, 4, 0)));

            AllocationResult res = r.allocate(cp, cp.getNode("0"), cp.getNode("1"), 100.0);
            if (thresholdDb < -28.9) {
                assertTrue(res.isBlocked(), "XT -28.9 dB must violate a " + thresholdDb + " dB threshold");
                assertEquals(BlockingCause.CROSSTALK, res.blockingCause());
            } else {
                assertFalse(res.isBlocked(), "XT -28.9 dB satisfies a " + thresholdDb + " dB threshold");
            }
        }
    }

    @Test
    @DisplayName("L9-j: a new circuit that pushes an active circuit above its XT threshold is blocked (XT_OTHERS)")
    void crosstalkOnActiveCircuits() {
        ModulationFormat lenient = mod("lenient", 5000, 4, -20);
        ModulationFormat strict = mod("strict", 5000, 4, -35);
        StandardIntegratedRMSCA r = rmsca();
        r.setModulationSelection(new FixedModulationSelection());
        ControlPlane cp = chain(1, 100.0, 2, 4, 0, List.of(lenient), config(true, true), r);
        cp.establishCircuit(circuit(cp, "active", 0, 0, 1, 0, 3, strict));

        AllocationResult res = r.allocate(cp, cp.getNode("0"), cp.getNode("1"), 100.0);
        assertTrue(res.isBlocked());
        assertEquals(BlockingCause.XT_OTHERS, res.blockingCause());
        // The temporary noise of the rejected candidate must have been removed
        assertEquals(0.0, PhysicalLayerModel.predictXtRatio(cp, new Path(cp.getLinks()), 1, 0, 3), 0.0);
    }

    @Test
    @DisplayName("CR-07: a transparent, less efficient format is preferred over a regenerated one")
    void transparentBeforeRegeneration() {
        // 2 hops x 300 km = 600 km: 64QAM (reach 400) needs a regenerator, 16QAM (reach 1000) does not.
        List<ModulationFormat> mods = List.of(mod("16QAM", 1000, 16, -20), mod("64QAM", 400, 64, -20));
        StandardIntegratedRMSCA r = rmsca();
        r.setModulationSelection(new DistanceAdaptiveModulationSelection());
        r.setRegeneratorAssignment(new AsSoonAsRequiredRegeneratorAssignment());
        ControlPlane cp = chain(2, 300.0, 1, 320, 5, mods, config(false, false), r);

        AllocationResult res = r.allocate(cp, cp.getNode("0"), cp.getNode("2"), 100.0);
        assertFalse(res.isBlocked());
        assertEquals("16QAM", res.modulation().name());
        assertTrue(res.regeneratorNodes().isEmpty(), "no regenerator when a transparent format reaches");

        // With only 64QAM available, the regenerated solution is used
        StandardIntegratedRMSCA r2 = rmsca();
        r2.setRegeneratorAssignment(new AsSoonAsRequiredRegeneratorAssignment());
        ControlPlane cp2 = chain(2, 300.0, 1, 320, 5, List.of(mod("64QAM", 400, 64, -20)), config(false, false), r2);
        AllocationResult res2 = r2.allocate(cp2, cp2.getNode("0"), cp2.getNode("2"), 100.0);
        assertFalse(res2.isBlocked());
        assertEquals(List.of(cp2.getNode("1")), res2.regeneratorNodes());
    }

    @Test
    @DisplayName("CR-06: regenerated candidates also go through the QoT/XT check of active circuits")
    void regeneratedCandidateChecksActiveCircuits() {
        // Reach is not violated (5000 km), but the SNR threshold (22 dB) fails transparently on 600 km
        // (ASE + XT: 20.1 dB) and passes on each 300 km segment (22.3 dB and 24.0 dB): the old code
        // accepted this "QoT restored by regenerators" case without checking the active circuits.
        ModulationFormat lenient = new ModulationFormat("lenient", 5000, 4, 22.0, -20, 32, 0.1);
        ModulationFormat strict = mod("strict", 5000, 4, -35);
        StandardIntegratedRMSCA r = rmsca();
        r.setModulationSelection(new FixedModulationSelection());
        r.setRegeneratorAssignment(new AsSoonAsRequiredRegeneratorAssignment());
        ControlPlane cp = chain(2, 300.0, 2, 4, 5, List.of(lenient), config(true, true), r);
        // Active circuit on core 1 of the first hop only; the new circuit can only use core 0 slots 0..3.
        cp.establishCircuit(circuit(cp, "active", 0, 0, 1, 0, 3, strict));
        cp.getLinks().get(1).getCore(1).getSpectrum().allocate(0, 3);   // core 1 unusable on hop 2

        AllocationResult res = r.allocate(cp, cp.getNode("0"), cp.getNode("2"), 100.0);
        assertTrue(res.isBlocked(), "the regenerated candidate raises the active circuit's XT to -27.2 dB > -35 dB");
        assertEquals(BlockingCause.XT_OTHERS, res.blockingCause());

        // Sanity check of the premise: without the active circuit the regenerated solution is accepted
        StandardIntegratedRMSCA r2 = rmsca();
        r2.setModulationSelection(new FixedModulationSelection());
        r2.setRegeneratorAssignment(new AsSoonAsRequiredRegeneratorAssignment());
        ControlPlane cp2 = chain(2, 300.0, 2, 4, 5, List.of(lenient), config(true, true), r2);
        AllocationResult ok = r2.allocate(cp2, cp2.getNode("0"), cp2.getNode("2"), 100.0);
        assertFalse(ok.isBlocked());
        assertEquals(List.of(cp2.getNode("1")), ok.regeneratorNodes(), "regenerator placed because of the SNR");
    }
}
