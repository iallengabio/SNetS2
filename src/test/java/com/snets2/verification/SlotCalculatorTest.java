package com.snets2.verification;

import com.snets2.metrics.PhysicalLayerModel;
import com.snets2.model.*;
import com.snets2.rmsca.modulation.SlotCalculator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Golden table for the slot-count rule (verification level L0-6, regression test for CR-10):
 * {@code n = ceil(R (1 + FEC) / (N_pol log2(M) f_slot)) + G}.
 */
class SlotCalculatorTest {

    private static final double SLOT = 12.5E9;

    private static ModulationFormat mod(double m) {
        return new ModulationFormat("M" + (int) m, 5000, m, 10, -20, 32, 0.1);
    }

    @Test
    @DisplayName("L0-6: slot count for bit rate x modulation x polarization x FEC x guard band")
    void goldenTable() {
        // {bitRate, M, guard, nPol, fec, expected}
        double[][] cases = {
            {100, 4, 0, 1, 0.00, 4},   // legacy rule: 100e9 / (2 * 12.5e9) = 4
            {100, 4, 0, 0, 0.00, 4},   // polarizationModes not configured => 1
            {100, 4, 0, 2, 0.00, 2},   // PDM halves the slots
            {100, 4, 0, 2, 0.25, 3},   // 125e9 / (2*2*12.5e9) = 2.5 -> 3
            {400, 64, 0, 2, 0.25, 4},  // 500e9 / (2*6*12.5e9) = 3.33 -> 4
            {200, 16, 0, 2, 0.00, 2},  // exact integer: 200e9 / (2*4*12.5e9) = 2
            {25, 4, 1, 1, 0.00, 2},    // 1 slot + 1 guard
            {800, 8, 1, 2, 0.07, 13},  // 856e9 / (2*3*12.5e9) = 11.41 -> 12, + 1 guard
        };
        for (double[] c : cases) {
            int n = SlotCalculator.requiredSlots(c[0], mod(c[1]), SLOT, (int) c[2], c[3], c[4]);
            assertEquals((int) c[5], n, "R=" + c[0] + " M=" + c[1] + " G=" + c[2] + " Npol=" + c[3] + " FEC=" + c[4]);
        }
    }

    @Test
    @DisplayName("Without a physical-layer config the control plane keeps the single-polarization, no-FEC rule")
    void controlPlaneWithoutPhysicalConfig() {
        Node a = new Node("A", 1, 1, 0);
        Node b = new Node("B", 1, 1, 0);
        Link l = new Link("A", "B", 10, List.of(new Core(0, List.of(), 10)), List.of());
        ControlPlane cp = new ControlPlane(new NetworkTopology(List.of(a, b), List.of(l), List.of(mod(4))), null, SLOT, 1, null);
        assertEquals(5, SlotCalculator.requiredSlots(100, mod(4), cp));
    }

    @Test
    @DisplayName("Signal bandwidth excludes the guard band slots")
    void signalBandwidthExcludesGuardBand() {
        assertEquals(3 * SLOT, PhysicalLayerModel.signalBandwidth(10, 13, 1, SLOT));
        assertEquals(4 * SLOT, PhysicalLayerModel.signalBandwidth(10, 13, 0, SLOT));
        assertEquals(1 * SLOT, PhysicalLayerModel.signalBandwidth(10, 10, 3, SLOT)); // never below one slot
    }
}
