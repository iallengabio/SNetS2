package com.snets2.verification;

import com.snets2.metrics.PhysicalLayerModel;
import com.snets2.model.*;
import com.snets2.rmsca.AlgorithmFactory;
import com.snets2.rmsca.StandardIntegratedRMSCA;
import com.snets2.rmsca.core.CoreSpectrumCandidate;
import com.snets2.rmsca.core.XtAwareGreedyCoreAndSpectrumAssignment;
import com.snets2.rmsca.modulation.DistanceAdaptiveModulationSelection;
import com.snets2.rmsca.routing.DijkstraRouting;
import com.snets2.rmsca.routing.Path;
import com.snets2.rmsca.spectrum.SpectrumInterval;
import com.snets2.rmsca.spectrum.SpectrumSearch;
import org.junit.jupiter.api.Test;

import java.util.*;

import static com.snets2.verification.QoTAwareModulationSelectionTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** XT-aware greedy (port of XtAwareGreedyAlgorithm of SNetS v1, #35): the feasible candidate with the smallest XT. */
class XtAwareGreedyCoreAndSpectrumAssignmentTest {

    private static List<CoreSpectrumCandidate> list(Iterable<CoreSpectrumCandidate> it) {
        List<CoreSpectrumCandidate> l = new ArrayList<>();
        it.forEach(l::add);
        return l;
    }

    @Test
    void registeredAsJointAlgorithm() {
        assertInstanceOf(XtAwareGreedyCoreAndSpectrumAssignment.class, AlgorithmFactory.createCoreAndSpectrum("xtawaregreedy"));
        assertInstanceOf(XtAwareGreedyCoreAndSpectrumAssignment.class, AlgorithmFactory.createCoreAndSpectrum("xtawaregreedyalgorithm"));
    }

    @Test
    void candidatesByIncreasingCrosstalkWithV1TieOrder() {
        ModulationFormat f = mod("f", 1E6, 4, -100, 100);
        ControlPlane cp = chain(1, 500, 3, 12, 0, List.of(f), config(true, false, true, true), rmsca(new DistanceAdaptiveModulationSelection()));
        Path path = new Path(cp.getLinks());
        cp.establishCircuit(circuit(cp, "a", 0, 1, 0, 3, LENIENT));   // cores mutually adjacent
        cp.establishCircuit(circuit(cp, "b", 0, 2, 0, 11, LENIENT));  // core 2 full

        List<CoreSpectrumCandidate> c = list(new XtAwareGreedyCoreAndSpectrumAssignment().candidates(cp, path, 4));
        double previous = -1;
        for (CoreSpectrumCandidate k : c) {
            if (k.slots() == null) continue;
            double xt = PhysicalLayerModel.predictXtRatio(cp, path, k.core(), k.slots().start(), k.slots().end());
            assertTrue(xt >= previous, "non-decreasing XT: " + c);
            previous = xt;
        }
        // Core 0 overlaps core 2 everywhere and core 1 on 0..3; core 1 overlaps core 2 everywhere. Least XT: core 0 from
        // slot 4 (only core 2), then core 1 (only core 2, ties with core 0 from slot 4 -> after core 0 by id)
        assertEquals(new CoreSpectrumCandidate(0, new SpectrumInterval(4, 7)), c.get(0));
        assertEquals(new CoreSpectrumCandidate(2, null), c.get(c.size() - 1), "cores without room at the end");
        // Without crosstalk: First-Fit core, First-Fit spectrum over all intervals
        ControlPlane noXt = chain(1, 500, 3, 12, 0, List.of(f), config(true, false, false, true), rmsca(new DistanceAdaptiveModulationSelection()));
        noXt.establishCircuit(circuit(noXt, "a", 0, 0, 0, 3, LENIENT));
        List<CoreSpectrumCandidate> ff = list(new XtAwareGreedyCoreAndSpectrumAssignment().candidates(noXt, new Path(noXt.getLinks()), 4));
        assertEquals(new CoreSpectrumCandidate(0, new SpectrumInterval(4, 7)), ff.get(0));
        assertEquals(new CoreSpectrumCandidate(1, new SpectrumInterval(0, 3)), ff.get(5));
    }

    /** Proposes a single fixed candidate: used to ask the RMSCA whether one (core, interval) is feasible. */
    private record Single(int core, SpectrumInterval slots) implements com.snets2.rmsca.core.ICoreAndSpectrumAssignment {
        @Override
        public Iterable<CoreSpectrumCandidate> candidates(ControlPlane cp, Path path, int numSlots) {
            return List.of(new CoreSpectrumCandidate(core, slots));
        }
    }

    @Test
    void sameChoiceAsTheExhaustiveSearchOfV1() {
        // v1: validate every free (core, interval) and keep the feasible one with the largest XT margin (smallest XT),
        // ties to the first found (core ascending, slot ascending).
        ModulationFormat f = mod("16QAM", 1E6, 16, 12.34, -22.42);
        StandardIntegratedRMSCA greedy = rmsca(new DistanceAdaptiveModulationSelection());
        greedy.setCoreAndSpectrumAssignment(new XtAwareGreedyCoreAndSpectrumAssignment());
        StandardIntegratedRMSCA probe = rmsca(new DistanceAdaptiveModulationSelection());

        int[][] edges = { {0, 1, 400}, {1, 2, 900}, {2, 3, 600}, {3, 0, 700}, {0, 2, 1200} };
        List<Node> nodes = new ArrayList<>();
        for (int i = 0; i < 4; i++) nodes.add(new Node(String.valueOf(i), 1000, 1000, 0));
        List<Link> links = new ArrayList<>();
        for (int[] e : edges) {
            links.add(new Link(String.valueOf(e[0]), String.valueOf(e[1]), e[2], hexagonal7(24), List.of()));
            links.add(new Link(String.valueOf(e[1]), String.valueOf(e[0]), e[2], hexagonal7(24), List.of()));
        }
        ControlPlane cp = new ControlPlane(new NetworkTopology(nodes, links, List.of(f)), greedy, 12.5E9, 0,
                config(true, true, true, false));

        Random rnd = new Random(35);
        List<String> active = new ArrayList<>();
        int accepted = 0, blocked = 0, nonTrivial = 0;
        for (int step = 0; step < 600; step++) {
            if (!active.isEmpty() && rnd.nextDouble() < 0.35) {
                cp.teardownCircuit(active.remove(rnd.nextInt(active.size())));
                continue;
            }
            int s = rnd.nextInt(4), d = rnd.nextInt(3);
            if (d >= s) d++;
            Node src = cp.getNode(String.valueOf(s)), dst = cp.getNode(String.valueOf(d));
            AllocationResult res = greedy.allocate(cp, src, dst, 100.0);

            // Exhaustive oracle on the same (single) path
            Path path = new DijkstraRouting().findPaths(cp, src, dst).get(0);
            int n = com.snets2.rmsca.modulation.SlotCalculator.requiredSlots(100.0, f, cp);
            CoreSpectrumCandidate best = null;
            double bestXt = Double.MAX_VALUE;
            int feasible = 0;
            for (int core = 0; core < 7; core++) {
                for (int start = 0; start + n <= 24; start++) {
                    if (!SpectrumSearch.fits(path.links(), core, start, n)) continue;
                    SpectrumInterval iv = new SpectrumInterval(start, start + n - 1);
                    probe.setCoreAndSpectrumAssignment(new Single(core, iv));
                    if (probe.allocate(cp, src, dst, 100.0).isBlocked()) continue;
                    feasible++;
                    double xt = PhysicalLayerModel.predictXtRatio(cp, path, core, start, start + n - 1);
                    if (xt < bestXt) {
                        bestXt = xt;
                        best = new CoreSpectrumCandidate(core, iv);
                    }
                }
            }
            if (best == null) {
                assertTrue(res.isBlocked(), "step " + step);
                blocked++;
                continue;
            }
            assertFalse(res.isBlocked(), "step " + step);
            assertEquals(best, new CoreSpectrumCandidate(res.coreIndices().get(0), new SpectrumInterval(res.startSlot(), res.endSlot())),
                    "step " + step);
            if (feasible > 1 && bestXt > 0) nonTrivial++;
            String id = "c" + step;
            cp.establishCircuit(res.toCircuit(id));
            active.add(id);
            accepted++;
        }
        assertTrue(accepted > 100 && blocked > 0 && nonTrivial > 20,
                "the scenario must exercise the choice: accepted " + accepted + ", blocked " + blocked + ", non-trivial " + nonTrivial);
    }

    /** Hexagonal 7-core MCF: core 0 in the centre, outer ring 1..6. */
    private static List<Core> hexagonal7(int slots) {
        return List.of(new Core(0, List.of(1, 2, 3, 4, 5, 6), slots), new Core(1, List.of(0, 2, 6), slots),
                new Core(2, List.of(0, 1, 3), slots), new Core(3, List.of(0, 2, 4), slots),
                new Core(4, List.of(0, 3, 5), slots), new Core(5, List.of(0, 4, 6), slots),
                new Core(6, List.of(0, 1, 5), slots));
    }
}
