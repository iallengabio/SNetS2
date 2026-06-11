package com.snets2.rmsca.routing;

import com.snets2.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class KShortestPathsRoutingTest {

    private ControlPlane cp;
    private Node nodeA, nodeB, nodeC, nodeD, nodeE, nodeF;
    private Link linkAC, linkCB, linkAD, linkDB, linkAE, linkEB, linkAF, linkFB;

    @BeforeEach
    void setUp() {
        nodeA = new Node("A", 10, 10, 5);
        nodeB = new Node("B", 10, 10, 5);
        nodeC = new Node("C", 10, 10, 5);
        nodeD = new Node("D", 10, 10, 5);
        nodeE = new Node("E", 10, 10, 5);
        nodeF = new Node("F", 10, 10, 5);

        Core core = new Core(0, List.of(), 320);

        linkAC = new Link("A", "C", 50.0, List.of(core), List.of());
        linkCB = new Link("C", "B", 50.0, List.of(core), List.of());

        linkAD = new Link("A", "D", 60.0, List.of(core), List.of());
        linkDB = new Link("D", "B", 60.0, List.of(core), List.of());

        linkAE = new Link("A", "E", 70.0, List.of(core), List.of());
        linkEB = new Link("E", "B", 70.0, List.of(core), List.of());

        linkAF = new Link("A", "F", 80.0, List.of(core), List.of());
        linkFB = new Link("F", "B", 80.0, List.of(core), List.of());

        NetworkTopology topology = new NetworkTopology(
            List.of(nodeA, nodeB, nodeC, nodeD, nodeE, nodeF),
            List.of(linkAC, linkCB, linkAD, linkDB, linkAE, linkEB, linkAF, linkFB),
            List.of()
        );
        cp = new ControlPlane(topology, null, 12.5E9, 1, null);
    }

    @Test
    void testKShortestPathsCorrectOrder() {
        KShortestPathsRouting ksp = new KShortestPathsRouting(3);
        List<Path> paths = ksp.findPaths(cp, nodeA, nodeB);

        assertNotNull(paths);
        assertEquals(3, paths.size());

        // Path 1 should be A -> C -> B (length 100)
        assertEquals(100.0, paths.get(0).getLength());
        assertEquals("C", paths.get(0).links().get(0).getDestinationId());

        // Path 2 should be A -> D -> B (length 120)
        assertEquals(120.0, paths.get(1).getLength());
        assertEquals("D", paths.get(1).links().get(0).getDestinationId());

        // Path 3 should be A -> E -> B (length 140)
        assertEquals(140.0, paths.get(2).getLength());
        assertEquals("E", paths.get(2).links().get(0).getDestinationId());
    }
}
