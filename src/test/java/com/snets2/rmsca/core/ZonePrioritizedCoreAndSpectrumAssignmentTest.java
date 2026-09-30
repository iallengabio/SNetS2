package com.snets2.rmsca.core;

import com.snets2.model.*;
import com.snets2.rmsca.AlgorithmFactory;
import com.snets2.rmsca.routing.Path;
import com.snets2.rmsca.spectrum.SpectrumInterval;
import com.snets2.rmsca.spectrum.SpectrumZones;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** CPCAS and RCCAS (ports of the crosstalk avoidance strategies of SNetS v1, #33) and the prioritized spectrum zones. */
class ZonePrioritizedCoreAndSpectrumAssignmentTest {

    private static final int SLOTS = 32;

    private ControlPlane cp;
    private Link linkAB, linkBC;
    private Path path;

    /** Hexagonal 7-core MCF: core 0 in the centre, outer ring 1..6. */
    private static List<Core> hexagonal7(int slots) {
        return List.of(new Core(0, List.of(1, 2, 3, 4, 5, 6), slots), new Core(1, List.of(0, 2, 6), slots),
                new Core(2, List.of(0, 1, 3), slots), new Core(3, List.of(0, 2, 4), slots),
                new Core(4, List.of(0, 3, 5), slots), new Core(5, List.of(0, 4, 6), slots),
                new Core(6, List.of(0, 1, 5), slots));
    }

    @BeforeEach
    void setUp() {
        Node a = new Node("A", 100, 100, 0), b = new Node("B", 100, 100, 0), c = new Node("C", 100, 100, 0);
        linkAB = new Link("A", "B", 100.0, hexagonal7(SLOTS), List.of());
        linkBC = new Link("B", "C", 300.0, hexagonal7(SLOTS), List.of());
        path = new Path(List.of(linkAB, linkBC));
        cp = new ControlPlane(new NetworkTopology(List.of(a, b, c), List.of(linkAB, linkBC), List.of()), null, 12.5E9, 0, null);
    }

    private static List<CoreSpectrumCandidate> list(Iterable<CoreSpectrumCandidate> it) {
        List<CoreSpectrumCandidate> l = new ArrayList<>();
        it.forEach(l::add);
        return l;
    }

    private void occupy(Link link, int core, int start, int end) {
        link.getCore(core).getSpectrum().allocate(start, end);
    }

    @Test
    void registeredAsJointAlgorithms() {
        assertInstanceOf(CorePrioritizationCoreAndSpectrumAssignment.class, AlgorithmFactory.createCoreAndSpectrum("cpcas"));
        assertInstanceOf(CorePrioritizationCoreAndSpectrumAssignment.Fallback.class, AlgorithmFactory.createCoreAndSpectrum("cpcas-fallback"));
        assertInstanceOf(RandomCoreZoneAssignment.class, AlgorithmFactory.createCoreAndSpectrum("rccas"));
        assertInstanceOf(RandomCoreZoneAssignment.Fallback.class, AlgorithmFactory.createCoreAndSpectrum("rccas-fallback"));
    }

    @Test
    void zonesKeepTheProportionsOfV1() {
        // v1: slots 1-137, 138-274, 275-320 of a 320-slot grid (1-based)
        assertEquals(List.of(new SpectrumInterval(0, 136), new SpectrumInterval(137, 273), new SpectrumInterval(274, 319)),
                SpectrumZones.zones(320, 3));
        assertEquals(List.of(new SpectrumInterval(0, 53), new SpectrumInterval(54, 108), new SpectrumInterval(109, 127)),
                SpectrumZones.zones(128, 3));
        assertEquals(List.of(new SpectrumInterval(0, 63), new SpectrumInterval(64, 127)), SpectrumZones.zones(128, 2));
        assertEquals(List.of(new SpectrumInterval(0, 127)), SpectrumZones.zones(128, 1));
        // search order of v1: g1 -> 1, 2, 3; g2 -> 2, 3, 1; g3 -> 3, 1, 2
        List<SpectrumInterval> z = SpectrumZones.zones(320, 3);
        assertEquals(List.of(z.get(1), z.get(2), z.get(0)), SpectrumZones.searchOrder(320, 3, 1));
        assertEquals(List.of(z.get(2), z.get(0), z.get(1)), SpectrumZones.searchOrder(320, 3, 2));
    }

    @Test
    void zonedFirstFitStaysInsideAZoneAndFallsBackToTheNextOnes() {
        // 32 slots, 3 colours: zones [0, 12], [13, 26], [27, 31]
        List<Link> links = path.links();
        assertEquals(new SpectrumInterval(13, 16), SpectrumZones.zonedFirstFit(links, 2, 1, 3, 4));
        assertEquals(new SpectrumInterval(27, 30), SpectrumZones.zonedFirstFit(links, 0, 2, 3, 4));
        // zone 2 has 5 slots: a 4-slot demand at 28 does not fit when slot 27 is taken on one link -> 28..31
        occupy(linkBC, 0, 27, 27);
        assertEquals(new SpectrumInterval(28, 31), SpectrumZones.zonedFirstFit(links, 0, 2, 3, 4));
        // 6 slots do not fit in zone 2: next zones in order (0, then 1), never across a zone boundary
        occupy(linkAB, 0, 3, 3);
        assertEquals(new SpectrumInterval(4, 9), SpectrumZones.zonedFirstFit(links, 0, 2, 3, 6));
        occupy(linkAB, 0, 8, 8);
        // zone 0 now has free runs 0-2, 4-7, 9-12 (< 6): zone 1
        assertEquals(new SpectrumInterval(13, 18), SpectrumZones.zonedFirstFit(links, 0, 2, 3, 6));
    }

    @Test
    void cpcasWeightsFollowV1() {
        CorePrioritizationCoreAndSpectrumAssignment cpcas = new CorePrioritizationCoreAndSpectrumAssignment();
        List<Integer> chosen = new ArrayList<>();
        for (int i = 0; i < 8; i++) chosen.add(list(cpcas.candidates(cp, path, 2)).get(0).core());
        // Lowest weight sum, ties to the highest id; the chosen core gets MAX and its neighbours +1:
        //   all 0 -> 6 (0, 1, 5 -> 1); {2, 3, 4} at 0 -> 4 (0 -> 2, 3 -> 1, 5 -> 2); 2 at 0 -> 2 (0 -> 3, 1 -> 2, 3 -> 2);
        //   1, 3, 5 at 2 -> 5; 1, 3 at 2 -> 3; 1 at 2 -> 1; only 0 left -> 0; all at MAX -> reset -> 6.
        assertEquals(List.of(6, 4, 2, 5, 3, 1, 0, 6), chosen);
        assertEquals(CorePrioritizationCoreAndSpectrumAssignment.MAX_WEIGHT, cpcas.weight(linkAB, 6));
        assertEquals(1, cpcas.weight(linkAB, 0), "reset, then +1 as neighbour of core 6");

        // The candidate of a call uses the zones of the core's group: core 6 (colour 1) -> zone 1
        CoreSpectrumCandidate c = list(new CorePrioritizationCoreAndSpectrumAssignment().candidates(cp, path, 4)).get(0);
        assertEquals(new CoreSpectrumCandidate(6, new SpectrumInterval(13, 16)), c);
    }

    @Test
    void cpcasProposesOneCoreAndTheFallbackVariantTheOthersByWeight() {
        for (int s = 0; s < SLOTS; s++) { occupy(linkAB, 6, s, s); }
        List<CoreSpectrumCandidate> single = list(new CorePrioritizationCoreAndSpectrumAssignment().candidates(cp, path, 4));
        assertEquals(List.of(new CoreSpectrumCandidate(6, null)), single);

        List<CoreSpectrumCandidate> fallback = list(new CorePrioritizationCoreAndSpectrumAssignment.Fallback().candidates(cp, path, 4));
        assertEquals(List.of(6, 5, 4, 3, 2, 1, 0), fallback.stream().map(CoreSpectrumCandidate::core).toList());
        assertNull(fallback.get(0).slots());
        assertEquals(new SpectrumInterval(0, 3), fallback.get(1).slots(), "core 5, colour 0 -> zone 0");
    }

    @Test
    void rccasIsReproducibleAndUniform() {
        RandomCoreZoneAssignment a = new RandomCoreZoneAssignment(), b = new RandomCoreZoneAssignment();
        a.setRandom(new Random(7));
        b.setRandom(new Random(7));
        int[] counts = new int[7];
        for (int i = 0; i < 7000; i++) {
            List<CoreSpectrumCandidate> ca = list(a.candidates(cp, path, 2)), cb = list(b.candidates(cp, path, 2));
            assertEquals(ca, cb);
            assertEquals(1, ca.size());
            counts[ca.get(0).core()]++;
        }
        for (int n : counts) assertTrue(n > 850 && n < 1150, Arrays.toString(counts));

        RandomCoreZoneAssignment f = new RandomCoreZoneAssignment.Fallback();
        f.setRandom(new Random(7));
        List<Integer> cores = list(f.candidates(cp, path, 2)).stream().map(CoreSpectrumCandidate::core).sorted().toList();
        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6), cores);
    }
}
