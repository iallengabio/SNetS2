package com.snets2.model;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ControlPlaneTest {

    private ControlPlane controlPlane;
    private Node nodeA;
    private Node nodeB;
    private Link linkAB;
    private Core core0;
    private ModulationFormat qpsk;

    @BeforeEach
    void setUp() {
        nodeA = new Node("A", 10, 10, 5);
        nodeB = new Node("B", 10, 10, 5);
        
        qpsk = new ModulationFormat("QPSK", 2000.0, 4.0, 12.0, -25.0, 32.0, 0.1);
        
        core0 = new Core(0, List.of(), 320); // 320 slots
        linkAB = new Link("A", "B", 100.0, List.of(core0), List.of());
        NetworkTopology topology = new NetworkTopology(List.of(nodeA, nodeB), List.of(linkAB), List.of(qpsk));
        
        controlPlane = new ControlPlane(topology, null, 12.5E9, 1, null);
    }

    @Test
    void testEstablishAndTeardownCircuit() {
        Circuit circuit = new Circuit("c1", nodeA, nodeB, List.of(linkAB), List.of(0), 10, 20, qpsk, 100.0);
        
        // Before establishment
        assertTrue(controlPlane.getActiveCircuits().isEmpty());
        assertEquals(10, nodeA.getAvailableTx());
        assertEquals(10, nodeB.getAvailableRx());
        assertTrue(core0.getSpectrum().isRangeFree(10, 20));

        // Establish
        controlPlane.establishCircuit(circuit);
        
        assertEquals(1, controlPlane.getActiveCircuits().size());
        assertEquals(9, nodeA.getAvailableTx());
        assertEquals(9, nodeB.getAvailableRx());
        assertFalse(core0.getSpectrum().isRangeFree(10, 20));
        assertTrue(core0.getSpectrum().isRangeFree(0, 9));
        assertTrue(core0.getSpectrum().isRangeFree(21, 319));
        assertFalse(core0.getSpectrum().isFree(10));
        assertTrue(core0.getSpectrum().isOccupied(20));

        // Teardown
        controlPlane.teardownCircuit("c1");
        
        assertTrue(controlPlane.getActiveCircuits().isEmpty());
        assertEquals(10, nodeA.getAvailableTx());
        assertEquals(10, nodeB.getAvailableRx());
        assertTrue(core0.getSpectrum().isRangeFree(10, 20));
    }

    @Test
    void testEstablishAndTeardownCircuitWithRegenerators() {
        Node nodeC = new Node("C", 10, 10, 5);
        Core core1 = new Core(0, List.of(), 320);
        Link linkAC = new Link("A", "C", 50.0, List.of(core0), List.of());
        Link linkCB = new Link("C", "B", 50.0, List.of(core1), List.of());
        
        NetworkTopology topology = new NetworkTopology(List.of(nodeA, nodeB, nodeC), List.of(linkAC, linkCB), List.of(qpsk));
        ControlPlane cp = new ControlPlane(topology, null, 12.5E9, 1, null);

        Circuit circuit = new Circuit("c2", nodeA, nodeB, List.of(linkAC, linkCB), List.of(0, 0), 10, 20, qpsk, 100.0, List.of(nodeC));

        assertEquals(5, nodeC.getAvailableRegenerators());

        // Establish
        cp.establishCircuit(circuit);

        assertEquals(4, nodeC.getAvailableRegenerators());
        assertEquals(9, nodeA.getAvailableTx());
        assertEquals(9, nodeB.getAvailableRx());

        // Teardown
        cp.teardownCircuit("c2");

        assertEquals(5, nodeC.getAvailableRegenerators());
        assertEquals(10, nodeA.getAvailableTx());
        assertEquals(10, nodeB.getAvailableRx());
    }

    @Test
    void testEstablishCircuitRegeneratorValidation() {
        Node nodeC = new Node("C", 10, 10, 0); // 0 regenerators
        Core core1 = new Core(0, List.of(), 320);
        Link linkAC = new Link("A", "C", 50.0, List.of(core0), List.of());
        Link linkCB = new Link("C", "B", 50.0, List.of(core1), List.of());
        
        NetworkTopology topology = new NetworkTopology(List.of(nodeA, nodeB, nodeC), List.of(linkAC, linkCB), List.of(qpsk));
        ControlPlane cp = new ControlPlane(topology, null, 12.5E9, 1, null);

        Circuit circuit = new Circuit("c3", nodeA, nodeB, List.of(linkAC, linkCB), List.of(0, 0), 10, 20, qpsk, 100.0, List.of(nodeC));

        // Should throw exception since nodeC has no regenerators
        assertThrows(IllegalStateException.class, () -> cp.establishCircuit(circuit));
    }
}
