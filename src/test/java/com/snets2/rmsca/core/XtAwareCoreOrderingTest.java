package com.snets2.rmsca.core;

import com.snets2.model.*;
import com.snets2.rmsca.routing.Path;
import com.snets2.rmsca.spectrum.CoreStaggeredFitSpectrumAssignment;
import com.snets2.rmsca.spectrum.FirstFitSpectrumAssignment;
import com.snets2.rmsca.spectrum.RandomFitSpectrumAssignment;
import com.snets2.rmsca.spectrum.SpectrumInterval;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** Peripheral-first order, slot-aware XT order and core-staggered spectrum on the 7-core hexagonal MCF (#18). */
class XtAwareCoreOrderingTest {

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
        Node a = new Node("A", 10, 10, 5), b = new Node("B", 10, 10, 5), c = new Node("C", 10, 10, 5);
        linkAB = new Link("A", "B", 100.0, hexagonal7(), List.of());
        linkBC = new Link("B", "C", 300.0, hexagonal7(), List.of());
        path = new Path(List.of(linkAB, linkBC));
        NetworkTopology topology = new NetworkTopology(List.of(a, b, c), List.of(linkAB, linkBC), List.of());
        cp = new ControlPlane(topology, null, 12.5E9, 1, null);
    }

    @Test
    void peripheralFirstOrderFillsNonAdjacentOuterCoresFirstAndCentreLast() {
        assertEquals(List.of(1, 3, 5, 2, 4, 6, 0), new PeripheralFirstCoreAssignment().selectCores(cp, path));
    }

    @Test
    void coreColoursNeverRepeatOnAdjacentCores() {
        Map<Integer, Integer> colours = PeripheralFirstCoreAssignment.coreColours(path);
        assertEquals(Map.of(1, 0, 3, 0, 5, 0, 2, 1, 4, 1, 6, 1, 0, 2), colours);
        for (Core core : linkAB.getCores().values()) {
            for (int adj : core.getAdjacentCores()) assertNotEquals(colours.get(core.getId()), colours.get(adj));
        }
    }

    @Test
    void xtAwareWithEmptyNetworkFollowsPeripheralOrder() {
        XtAwareCoreAssignment xt = new XtAwareCoreAssignment();
        assertEquals(List.of(1, 3, 5, 2, 4, 6, 0), xt.selectCores(cp, path, 4, new FirstFitSpectrumAssignment()));
        assertEquals(List.of(1, 3, 5, 2, 4, 6, 0), xt.selectCores(cp, path));
    }

    @Test
    void xtAwareLooksAtTheSlotsTheCandidateWouldGet() {
        // Core 2 carries slots 0-3 on both links: First-Fit would put a 4-slot demand at 0-3 in cores 1 and 3
        // (both adjacent to 2) and in core 0; cores 4, 5, 6 are not adjacent to 2.
        linkAB.getCore(2).getSpectrum().allocate(0, 3);
        linkBC.getCore(2).getSpectrum().allocate(0, 3);
        // Core 5 carries slots 10-13 only on the long link, away from the First-Fit interval of its neighbours.
        linkBC.getCore(5).getSpectrum().allocate(10, 13);

        List<Integer> order = new XtAwareCoreAssignment().selectCores(cp, path, 4, new FirstFitSpectrumAssignment());
        // cost 0: 5, 2 (First-Fit gives it slots 4-7), 4, 6 in peripheral order; then 1, 3, 0 (cost 1600 each)
        assertEquals(List.of(5, 2, 4, 6, 1, 3, 0), order);
        assertEquals(0.0, XtAwareCoreAssignment.overlapCost(path, 4, new SpectrumInterval(0, 3)));
        assertEquals(4 * 100.0 + 4 * 300.0, XtAwareCoreAssignment.overlapCost(path, 1, new SpectrumInterval(0, 3)));
    }

    @Test
    void xtAwareIsDeterministicWithRandomizedSpectrumPolicy() {
        linkAB.getCore(1).getSpectrum().allocate(0, 5);
        XtAwareCoreAssignment xt = new XtAwareCoreAssignment();
        List<Integer> first = xt.selectCores(cp, path, 3, new RandomFitSpectrumAssignment());
        for (int i = 0; i < 5; i++) assertEquals(first, xt.selectCores(cp, path, 3, new RandomFitSpectrumAssignment()));
    }

    @Test
    void xtAwarePutsCoresWithoutFreeIntervalLast() {
        for (Link link : path.links()) link.getCore(1).getSpectrum().allocate(0, SLOTS - 1);
        List<Integer> order = new XtAwareCoreAssignment().selectCores(cp, path, 2, new FirstFitSpectrumAssignment());
        assertEquals(1, order.get(order.size() - 1));
        assertEquals(7, order.stream().distinct().count());
    }

    @Test
    void coreStaggeredFitUsesADifferentStartingPointPerColour() {
        CoreStaggeredFitSpectrumAssignment sa = new CoreStaggeredFitSpectrumAssignment();
        assertEquals(new SpectrumInterval(0, 3), sa.findSlots(cp, path, 1, 4));   // colour 0: First-Fit
        assertEquals(new SpectrumInterval(16, 19), sa.findSlots(cp, path, 2, 4)); // colour 1: Last-Fit
        assertEquals(new SpectrumInterval(10, 13), sa.findSlots(cp, path, 0, 4)); // colour 2: middle of the band

        // Centre core wraps around when the upper part of the band is busy
        for (Link link : path.links()) link.getCore(0).getSpectrum().allocate(10, SLOTS - 1);
        assertEquals(new SpectrumInterval(0, 3), sa.findSlots(cp, path, 0, 4));
        // No interval left
        for (Link link : path.links()) link.getCore(0).getSpectrum().allocate(0, 9);
        assertNull(sa.findSlots(cp, path, 0, 1));
    }
}
