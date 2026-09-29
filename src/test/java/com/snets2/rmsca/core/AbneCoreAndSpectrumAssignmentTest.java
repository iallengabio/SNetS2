package com.snets2.rmsca.core;

import com.snets2.model.*;
import com.snets2.rmsca.AlgorithmFactory;
import com.snets2.rmsca.StandardIntegratedRMSCA;
import com.snets2.rmsca.modulation.FixedModulationSelection;
import com.snets2.rmsca.routing.DijkstraRouting;
import com.snets2.rmsca.routing.Path;
import com.snets2.rmsca.spectrum.FirstFitSpectrumAssignment;
import com.snets2.rmsca.spectrum.SpectrumInterval;
import com.snets2.rmsca.spectrum.SpectrumSearch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** ABNE (port of CSBASDM/CSBASDM2 of SNetS v1, #32) and the joint core and spectrum contract of the RMSCA. */
class AbneCoreAndSpectrumAssignmentTest {

    private static final int SLOTS = 20;

    private ControlPlane cp;
    private Link linkAB, linkBC;
    private Path path;

    /** Hexagonal 7-core MCF: core 0 in the centre, outer ring 1..6. */
    private static List<Core> hexagonal7() {
        return List.of(new Core(0, List.of(1, 2, 3, 4, 5, 6), SLOTS), new Core(1, List.of(0, 2, 6), SLOTS),
                new Core(2, List.of(0, 1, 3), SLOTS), new Core(3, List.of(0, 2, 4), SLOTS),
                new Core(4, List.of(0, 3, 5), SLOTS), new Core(5, List.of(0, 4, 6), SLOTS),
                new Core(6, List.of(0, 1, 5), SLOTS));
    }

    @BeforeEach
    void setUp() {
        Node a = new Node("A", 100, 100, 0), b = new Node("B", 100, 100, 0), c = new Node("C", 100, 100, 0);
        linkAB = new Link("A", "B", 100.0, hexagonal7(), List.of());
        linkBC = new Link("B", "C", 300.0, hexagonal7(), List.of());
        path = new Path(List.of(linkAB, linkBC));
        ModulationFormat bpsk = new ModulationFormat("BPSK", 10000, 2, 0, 0, 12.5, 0);
        NetworkTopology topology = new NetworkTopology(List.of(a, b, c), List.of(linkAB, linkBC), List.of(bpsk));
        cp = new ControlPlane(topology, null, 12.5E9, 0, null);
    }

    private static List<CoreSpectrumCandidate> list(Iterable<CoreSpectrumCandidate> it) {
        List<CoreSpectrumCandidate> l = new ArrayList<>();
        it.forEach(l::add);
        return l;
    }

    private List<Integer> rotation(ICoreAndSpectrumAssignment a, int calls) {
        List<Integer> cores = new ArrayList<>();
        for (int i = 0; i < calls; i++) cores.add(list(a.candidates(cp, path, 2)).get(0).core());
        return cores;
    }

    private void occupy(int core, int start, int end) {
        linkAB.getCore(core).getSpectrum().allocate(start, end);
        linkBC.getCore(core).getSpectrum().allocate(start, end);
    }

    @Test
    void registeredAsJointAlgorithms() {
        assertTrue(AlgorithmFactory.isJointCoreAndSpectrum("abne"));
        assertTrue(AlgorithmFactory.isJointCoreAndSpectrum("CSBASDM"));
        assertFalse(AlgorithmFactory.isJointCoreAndSpectrum("xtawarecore"));
        assertInstanceOf(AbneCoreAndSpectrumAssignment.Abne2.class, AlgorithmFactory.createCoreAndSpectrum("abne2"));
        assertInstanceOf(AbneCoreAndSpectrumAssignment.Fallback.class, AlgorithmFactory.createCoreAndSpectrum("abne-fallback"));
    }

    @Test
    void roundRobinOverTheCoresOneCorePerCall() {
        AbneCoreAndSpectrumAssignment abne = new AbneCoreAndSpectrumAssignment();
        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 0, 1, 2), rotation(abne, 10));
        assertEquals(1, list(abne.candidates(cp, path, 2)).size(), "abne proposes a single core");
    }

    @Test
    void abne2UsesTheCentralCoreOnceEverySixRounds() {
        // CSBASDM2 of v1 (QUANTCENTRALCORE = 5): rounds 1-5 skip core 0, round 6 starts with it
        List<Integer> expected = new ArrayList<>();
        for (int round = 0; round < 5; round++) expected.addAll(List.of(1, 2, 3, 4, 5, 6));
        expected.addAll(List.of(0, 1, 2, 3, 4, 5, 6));
        expected.addAll(List.of(1, 2, 3));
        assertEquals(expected, rotation(new AbneCoreAndSpectrumAssignment.Abne2(), expected.size()));
    }

    @Test
    void spectrumPolicyByCoreColour() {
        // colours on the hexagonal MCF: 1, 3, 5 -> 0 (First-Fit); 2, 4, 6 -> 1 (Last-Fit); 0 -> 2 (medium fit)
        List<Link> links = path.links();
        assertEquals(new SpectrumInterval(0, 3), AbneCoreAndSpectrumAssignment.assignSpectrum(links, 1, 0, 4));
        assertEquals(new SpectrumInterval(16, 19), AbneCoreAndSpectrumAssignment.assignSpectrum(links, 2, 1, 4));
        assertEquals(new SpectrumInterval(10, 13), AbneCoreAndSpectrumAssignment.assignSpectrum(links, 0, 2, 4));

        // Medium fit (v1): the free interval whose first slot is closest to slot N/2 = 10, ties to the lowest start
        occupy(0, 8, 12);               // on both links
        linkBC.getCore(0).getSpectrum().allocate(13, 14);
        // free starts for 4 slots: 0..4 and 15, 16; distances to 10: 4 -> 6, 15 -> 5
        assertEquals(new SpectrumInterval(15, 18), SpectrumSearch.mediumFit(links, 0, 4));
        linkAB.getCore(0).getSpectrum().allocate(15, 15);
        // now 15 is taken on one link: 16 (distance 6) ties with 4 (distance 6) -> lowest start
        assertEquals(new SpectrumInterval(4, 7), SpectrumSearch.mediumFit(links, 0, 4));

        // Candidates of a call use the policy of the round-robin core
        AbneCoreAndSpectrumAssignment abne = new AbneCoreAndSpectrumAssignment();
        assertEquals(new CoreSpectrumCandidate(0, new SpectrumInterval(4, 7)), list(abne.candidates(cp, path, 4)).get(0));
        assertEquals(new CoreSpectrumCandidate(1, new SpectrumInterval(0, 3)), list(abne.candidates(cp, path, 4)).get(0));
        assertEquals(new CoreSpectrumCandidate(2, new SpectrumInterval(16, 19)), list(abne.candidates(cp, path, 4)).get(0));
    }

    @Test
    void abneBlocksWhenTheRoundRobinCoreIsFullAndTheFallbackVariantTriesTheOthers() {
        occupy(0, 0, SLOTS - 1);
        List<CoreSpectrumCandidate> abne = list(new AbneCoreAndSpectrumAssignment().candidates(cp, path, 4));
        assertEquals(List.of(new CoreSpectrumCandidate(0, null)), abne);

        List<CoreSpectrumCandidate> fallback = list(new AbneCoreAndSpectrumAssignment.Fallback().candidates(cp, path, 4));
        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6), fallback.stream().map(CoreSpectrumCandidate::core).toList());
        assertNull(fallback.get(0).slots());
        assertEquals(new SpectrumInterval(0, 3), fallback.get(1).slots());
        assertEquals(new SpectrumInterval(16, 19), fallback.get(2).slots());
    }

    @Test
    void rmscaUsesTheJointAssignment() {
        StandardIntegratedRMSCA rmsca = new StandardIntegratedRMSCA();
        rmsca.setRouting(new DijkstraRouting());
        rmsca.setModulationSelection(new FixedModulationSelection());
        rmsca.setCoreAssignment(new FirstFitCoreAssignment());          // replaced by the joint assignment
        rmsca.setSpectrumAssignment(new FirstFitSpectrumAssignment());
        rmsca.setCoreAndSpectrumAssignment(new AbneCoreAndSpectrumAssignment());
        occupy(0, 0, SLOTS - 1);

        AllocationResult first = rmsca.allocate(cp, cp.getNode("A"), cp.getNode("C"), 12.5);
        assertTrue(first.isBlocked(), "round-robin core 0 is full: abne does not try the others");
        assertEquals(com.snets2.metrics.BlockingCause.FRAGMENTATION, first.blockingCause());
        assertEquals(0, first.blockingCoreId());

        AllocationResult second = rmsca.allocate(cp, cp.getNode("A"), cp.getNode("C"), 12.5);
        assertFalse(second.isBlocked());
        assertEquals(1, second.coreIndices().get(0));
        assertEquals(0, second.startSlot(), "core 1 (colour 0) uses First-Fit");

        rmsca.setCoreAndSpectrumAssignment(null); // back to First-Fit core + First-Fit spectrum
        AllocationResult sequential = rmsca.allocate(cp, cp.getNode("A"), cp.getNode("C"), 12.5);
        assertEquals(1, sequential.coreIndices().get(0));
    }
}
