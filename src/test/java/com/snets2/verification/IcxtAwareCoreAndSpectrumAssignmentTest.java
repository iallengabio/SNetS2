package com.snets2.verification;

import com.snets2.metrics.BlockingCause;
import com.snets2.metrics.PhysicalLayerModel;
import com.snets2.model.*;
import com.snets2.rmsca.AlgorithmFactory;
import com.snets2.rmsca.StandardIntegratedRMSCA;
import com.snets2.rmsca.core.CoreSpectrumCandidate;
import com.snets2.rmsca.core.IcxtAwareCoreAndSpectrumAssignment;
import com.snets2.rmsca.modulation.DistanceAdaptiveModulationSelection;
import com.snets2.rmsca.routing.Path;
import com.snets2.rmsca.spectrum.SpectrumInterval;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.snets2.verification.QoTAwareModulationSelectionTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** ICXTAA (port of IcxtAwareAlgorithm of SNetS v1, #34): every free interval, in zone order, validated by the RMSCA. */
class IcxtAwareCoreAndSpectrumAssignmentTest {

    /** New circuits: tolerant to the XT they suffer (-20 dB), 4 slots at 100 Gbps. */
    private static final ModulationFormat NEW = mod("new", 1E6, 4, -100, -20);
    /** Victim already established: XT threshold below the -24.9 dB that one overlapping neighbour injects at 500 km. */
    private static final ModulationFormat STRICT = mod("strict", 1E6, 4, -100, -30);

    private static List<CoreSpectrumCandidate> list(Iterable<CoreSpectrumCandidate> it) {
        List<CoreSpectrumCandidate> l = new ArrayList<>();
        it.forEach(l::add);
        return l;
    }

    @Test
    void registeredAsJointAlgorithm() {
        assertInstanceOf(IcxtAwareCoreAndSpectrumAssignment.class, AlgorithmFactory.createCoreAndSpectrum("icxtaa"));
    }

    @Test
    void candidatesAreEveryFreeIntervalInZoneOrderWithCoresInDecreasingOrder() {
        // 2 adjacent cores, 16 slots: colours 0 (core 0) and 1 (core 1); zones [0, 7] and [8, 15]
        ControlPlane cp = chain(1, 500, 2, 16, 0, List.of(NEW), config(true, false, true, true), rmsca(new DistanceAdaptiveModulationSelection()));
        Path path = new Path(cp.getLinks());
        cp.establishCircuit(circuit(cp, "a", 0, 1, 0, 9, LENIENT));   // core 1 free: 10..15
        cp.establishCircuit(circuit(cp, "b", 0, 0, 5, 15, LENIENT));  // core 0 free: 0..4

        List<CoreSpectrumCandidate> c = list(new IcxtAwareCoreAndSpectrumAssignment().candidates(cp, path, 4));
        List<CoreSpectrumCandidate> expected = List.of(
            // core 1, own zone [8, 15]: starts 10, 11, 12; zone [0, 7]: none
            new CoreSpectrumCandidate(1, new SpectrumInterval(10, 13)), new CoreSpectrumCandidate(1, new SpectrumInterval(11, 14)),
            new CoreSpectrumCandidate(1, new SpectrumInterval(12, 15)),
            // core 0, own zone [0, 7]: starts 0, 1
            new CoreSpectrumCandidate(0, new SpectrumInterval(0, 3)), new CoreSpectrumCandidate(0, new SpectrumInterval(1, 4)));
        assertEquals(expected, c);

        cp.establishCircuit(circuit(cp, "c", 0, 1, 10, 15, LENIENT)); // core 1 full
        assertEquals(new CoreSpectrumCandidate(1, null), list(new IcxtAwareCoreAndSpectrumAssignment().candidates(cp, path, 4)).get(0));
    }

    @Test
    void triesTheNextIntervalsOfACoreWhereOneIntervalPerCoreBlocks() {
        // Core 1 is full: a STRICT victim on 0..3 and lenient circuits on 4..15. Core 0 is empty.
        // Any new circuit on core 0 suffers XT = hL = -24.9 dB (< -20, fine) and injects the same density into the
        // slots it overlaps in core 1.
        StandardIntegratedRMSCA firstFit = rmsca(new DistanceAdaptiveModulationSelection());
        ControlPlane cp = chain(1, 500, 2, 16, 0, List.of(NEW), config(true, false, true, true), firstFit);
        Circuit victim = circuit(cp, "victim", 0, 1, 0, 3, STRICT);
        cp.establishCircuit(victim);
        cp.establishCircuit(circuit(cp, "filler", 0, 1, 4, 15, LENIENT));

        AllocationResult sequential = firstFit.allocate(cp, cp.getNode("0"), cp.getNode("1"), 100.0);
        assertTrue(sequential.isBlocked(), "First-Fit core + First-Fit spectrum proposes only slots 0..3 of core 0");
        assertEquals(BlockingCause.XT_OTHERS, sequential.blockingCause());

        StandardIntegratedRMSCA icxtaa = rmsca(new DistanceAdaptiveModulationSelection());
        icxtaa.setCoreAndSpectrumAssignment(new IcxtAwareCoreAndSpectrumAssignment());
        cp = chain(1, 500, 2, 16, 0, List.of(NEW), config(true, false, true, true), icxtaa);
        victim = circuit(cp, "victim", 0, 1, 0, 3, STRICT);
        cp.establishCircuit(victim);
        cp.establishCircuit(circuit(cp, "filler", 0, 1, 4, 15, LENIENT));

        AllocationResult res = icxtaa.allocate(cp, cp.getNode("0"), cp.getNode("1"), 100.0);
        assertFalse(res.isBlocked());
        assertEquals(0, res.coreIndices().get(0));
        // The victim's XT is averaged over its 4 slots: overlapping 2 of them gives hL/2 = -27.9 dB (> -30, rejected),
        // 1 of them hL/4 = -30.9 dB (accepted). The first acceptable interval starts at slot 3.
        assertEquals(3, res.startSlot(), "first interval of core 0 that keeps the victim within its threshold");
        cp.establishCircuit(res.toCircuit("new"));
        double victimXt = PhysicalLayerModel.predictXtRatio(cp, new Path(victim.getPath()), 1, 0, 3);
        assertTrue(victimXt <= STRICT.getCrosstalkThresholdLinear());
    }
}
