package com.snets2.metrics;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

public class MetricsCalculationsTest {

    @Test
    public void testFreeBandsDetection() {
        BitSet slots = new BitSet(10);
        // Occupy slots 3, 4, and 7
        slots.set(3);
        slots.set(4);
        slots.set(7);

        // Expected free bands: [0, 2], [5, 6], [8, 9]
        List<int[]> freeBands = ExternalFragmentationMetrics.getFreeSpectrumBandsFromBitSet(slots, 10);
        
        assertEquals(3, freeBands.size());
        assertArrayEquals(new int[]{0, 2}, freeBands.get(0));
        assertArrayEquals(new int[]{5, 6}, freeBands.get(1));
        assertArrayEquals(new int[]{8, 9}, freeBands.get(2));
    }

    @Test
    public void testExternalFragmentation() {
        List<int[]> freeBands = new ArrayList<>();
        freeBands.add(new int[]{0, 9});   // size 10
        freeBands.add(new int[]{20, 24}); // size 5
        // total = 15, max = 10
        // EF = 1 - 10/15 = 0.3333333333
        
        double ef = ExternalFragmentationMetrics.calculateExternalFragmentation(freeBands);
        assertEquals(1.0 - (10.0 / 15.0), ef, 1e-9);

        // Empty free bands
        double efEmpty = ExternalFragmentationMetrics.calculateExternalFragmentation(new ArrayList<>());
        assertEquals(0.0, efEmpty);
    }

    @Test
    public void testEntropyFragmentation() {
        List<int[]> freeBands = new ArrayList<>();
        freeBands.add(new int[]{0, 9});   // size 10
        freeBands.add(new int[]{20, 24}); // size 5
        // total = 15
        // p1 = 10/15, p2 = 5/15
        // Entropy = - (10/15 * ln(10/15) + 5/15 * ln(5/15))
        // Max entropy = ln(30)
        
        double entropy = ExternalFragmentationMetrics.calculateEntropyExternalFragmentation(freeBands, 30);
        double expectedRaw = - ((10.0 / 15.0) * Math.log(10.0 / 15.0) + (5.0 / 15.0) * Math.log(5.0 / 15.0));
        double expectedNorm = expectedRaw / Math.log(30);

        assertEquals(expectedNorm, entropy, 1e-9);

        // Empty case
        double entropyEmpty = ExternalFragmentationMetrics.calculateEntropyExternalFragmentation(new ArrayList<>(), 30);
        assertEquals(0.0, entropyEmpty);
    }

    @Test
    public void testRelativeFragmentation() {
        List<int[]> freeBands = new ArrayList<>();
        freeBands.add(new int[]{0, 9});   // size 10
        freeBands.add(new int[]{20, 24}); // size 5
        // total = 15

        // For c = 6: block of size 5 is unusable, size 10 is usable.
        // Unusable = 5. Relative Frag = 5 / 15
        double rf6 = RelativeFragmentationMetrics.calculateRelativeFragmentation(freeBands, 6);
        assertEquals(5.0 / 15.0, rf6, 1e-9);

        // For c = 12: all blocks are unusable. Unusable = 15. Relative Frag = 15 / 15 = 1.0
        double rf12 = RelativeFragmentationMetrics.calculateRelativeFragmentation(freeBands, 12);
        assertEquals(1.0, rf12, 1e-9);

        // For c = 3: all blocks are usable. Unusable = 0. Relative Frag = 0.0
        double rf3 = RelativeFragmentationMetrics.calculateRelativeFragmentation(freeBands, 3);
        assertEquals(0.0, rf3, 1e-9);
    }
}
