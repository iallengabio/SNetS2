package com.snets2.rmsca.core;

import com.snets2.model.*;
import com.snets2.rmsca.routing.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class MinCrosstalkCoreAssignmentTest {

    private ControlPlane cp;
    private Path path;
    private Core core0, core1, core2;

    @BeforeEach
    void setUp() {
        Node nodeA = new Node("A", 10, 10, 5);
        Node nodeB = new Node("B", 10, 10, 5);

        // Core 0 adjacent to 1
        core0 = new Core(0, List.of(1), 10);
        // Core 1 adjacent to 0 and 2
        core1 = new Core(1, List.of(0, 2), 10);
        // Core 2 adjacent to 1
        core2 = new Core(2, List.of(1), 10);

        // Populate core 1 with some allocations
        core1.getSpectrum().allocate(0, 2); // 3 slots occupied

        Link linkAB = new Link("A", "B", 100.0, List.of(core0, core1, core2), List.of());
        path = new Path(List.of(linkAB));

        NetworkTopology topology = new NetworkTopology(List.of(nodeA, nodeB), List.of(linkAB), List.of());
        cp = new ControlPlane(topology, null, 12.5E9, 1, null);
    }

    @Test
    void testMinCrosstalkCoreSorting() {
        MinCrosstalkCoreAssignment assignment = new MinCrosstalkCoreAssignment();
        List<Integer> selected = assignment.selectCores(cp, path);

        assertNotNull(selected);
        assertEquals(3, selected.size());

        // Core 1 should be first because its neighbors (0 and 2) are completely empty (adjacentOccupancy = 0)
        assertEquals(1, selected.get(0));

        // Core 0 and 2 are adjacent to Core 1 which has 3 occupied slots (adjacentOccupancy = 3)
        // By sub-sorting numerically, Core 0 comes before Core 2
        assertEquals(0, selected.get(1));
        assertEquals(2, selected.get(2));
    }
}
