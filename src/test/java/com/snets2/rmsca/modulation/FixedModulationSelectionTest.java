package com.snets2.rmsca.modulation;

import com.snets2.model.*;
import com.snets2.rmsca.routing.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class FixedModulationSelectionTest {

    private ControlPlane cp;
    private Path path;
    private ModulationFormat bpsk;
    private ModulationFormat qpsk;

    @BeforeEach
    void setUp() {
        Node nodeA = new Node("A", 10, 10, 5);
        Node nodeB = new Node("B", 10, 10, 5);

        // BPSK: reach 2000km, M = 1
        bpsk = new ModulationFormat("BPSK", 2000.0, 1.0, 10.0, -20.0, 120.0, 0.1);
        // QPSK: reach 1000km, M = 2
        qpsk = new ModulationFormat("QPSK", 1000.0, 2.0, 10.0, -20.0, 120.0, 0.2);

        Link linkAB = new Link("A", "B", 100.0, List.of(), List.of());
        path = new Path(List.of(linkAB));

        NetworkTopology topology = new NetworkTopology(List.of(nodeA, nodeB), List.of(linkAB), List.of(bpsk, qpsk));
        cp = new ControlPlane(topology, null, 12.5E9, 1, null);
    }

    @Test
    void testSelectsBpskRegardlessOfDistance() {
        FixedModulationSelection fixed = new FixedModulationSelection();
        ModulationResult res = fixed.selectModulation(cp, path, 100.0);

        assertNotNull(res);
        assertEquals("BPSK", res.format().name());
    }
}
