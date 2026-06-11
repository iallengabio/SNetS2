package com.snets2.rmsca.spectrum;

import com.snets2.model.*;
import com.snets2.rmsca.routing.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SpectrumAssignmentTest {

    private ControlPlane cp;
    private Path path;
    private Core core;

    @BeforeEach
    void setUp() {
        Node nodeA = new Node("A", 10, 10, 5);
        Node nodeB = new Node("B", 10, 10, 5);

        // Core with 10 slots
        core = new Core(0, List.of(), 10);

        Link linkAB = new Link("A", "B", 100.0, List.of(core), List.of());
        path = new Path(List.of(linkAB));

        NetworkTopology topology = new NetworkTopology(List.of(nodeA, nodeB), List.of(linkAB), List.of());
        cp = new ControlPlane(topology, null, 12.5E9, 1, null);
    }

    @Test
    void testLastFitSpectrumAssignment() {
        // Occupy slots 2-3 and 7-8
        // Free blocks: [0-1] (size 2), [4-6] (size 3), [9-9] (size 1)
        core.getSpectrum().allocate(2, 3);
        core.getSpectrum().allocate(7, 8);

        LastFitSpectrumAssignment lf = new LastFitSpectrumAssignment();
        
        // Request 2 slots. LastFit should search from slot 8 downwards.
        // It should find slots 5-6 (which is part of [4-6]) instead of 0-1.
        SpectrumInterval slots = lf.findSlots(cp, path, 0, 2);
        assertNotNull(slots);
        assertEquals(5, slots.start());
        assertEquals(6, slots.end());
    }

    @Test
    void testExactFitSpectrumAssignment() {
        // Free blocks: [0-4] (size 5), [7-9] (size 3)
        // Occupy slots 5-6
        core.getSpectrum().allocate(5, 6);

        ExactFitSpectrumAssignment ef = new ExactFitSpectrumAssignment();

        // Request 3 slots.
        // Option 1: slots 0-2 (part of size 5 block).
        // Option 2: slots 7-9 (exact fit for size 3 block).
        // ExactFit should choose 7-9.
        SpectrumInterval slots = ef.findSlots(cp, path, 0, 3);
        assertNotNull(slots);
        assertEquals(7, slots.start());
        assertEquals(9, slots.end());
    }
}
