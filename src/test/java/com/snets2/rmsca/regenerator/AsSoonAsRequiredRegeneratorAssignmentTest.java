package com.snets2.rmsca.regenerator;

import com.snets2.model.*;
import com.snets2.rmsca.routing.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AsSoonAsRequiredRegeneratorAssignmentTest {

    private ControlPlane cp;
    private Node nodeA;
    private Node nodeB;
    private Node nodeC;
    private Link linkAC;
    private Link linkCB;
    private ModulationFormat mod;
    private AsSoonAsRequiredRegeneratorAssignment aar;

    @BeforeEach
    void setUp() {
        nodeA = new Node("A", 10, 10, 5);
        nodeB = new Node("B", 10, 10, 5);
        nodeC = new Node("C", 10, 10, 2); // 2 regenerators available

        mod = new ModulationFormat("TestMod", 80.0, 2.0, 10.0, -20.0, 120.0, 0.1); // Max reach 80 km
        
        Core coreAC = new Core(0, List.of(), 320);
        Core coreCB = new Core(0, List.of(), 320);
        
        linkAC = new Link("A", "C", 50.0, List.of(coreAC), List.of());
        linkCB = new Link("C", "B", 50.0, List.of(coreCB), List.of());
        
        NetworkTopology topology = new NetworkTopology(List.of(nodeA, nodeB, nodeC), List.of(linkAC, linkCB), List.of(mod));
        cp = new ControlPlane(topology, null, 12.5E9, 1, null);
        aar = new AsSoonAsRequiredRegeneratorAssignment();
    }

    @Test
    void testRegeneratorAssignedCorrectly() {
        Path path = new Path(List.of(linkAC, linkCB)); // Total distance = 100 km, max reach = 80 km
        List<Node> regens = aar.assignRegenerators(cp, path, 0, mod, 10, 20, 100.0);
        
        assertNotNull(regens);
        assertEquals(1, regens.size());
        assertEquals("C", regens.get(0).getId());
    }

    @Test
    void testRegeneratorBlockedIfNoRegensAvailable() {
        nodeC.consumeRegenerators(2); // Consume all available regenerators
        Path path = new Path(List.of(linkAC, linkCB));
        
        List<Node> regens = aar.assignRegenerators(cp, path, 0, mod, 10, 20, 100.0);
        assertNull(regens); // Should be blocked
    }
}
