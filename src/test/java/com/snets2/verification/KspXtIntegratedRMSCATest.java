package com.snets2.verification;

import com.snets2.model.*;
import com.snets2.rmsca.AlgorithmFactory;
import com.snets2.rmsca.KspXtIntegratedRMSCA;
import com.snets2.rmsca.StandardIntegratedRMSCA;
import com.snets2.rmsca.core.ColourFitCoreAndSpectrumAssignment;
import com.snets2.rmsca.core.CoreSpectrumCandidate;
import com.snets2.rmsca.modulation.DistanceAdaptiveModulationSelection;
import com.snets2.rmsca.routing.DijkstraRouting;
import com.snets2.rmsca.routing.KShortestPathsRouting;
import com.snets2.rmsca.routing.Path;
import com.snets2.rmsca.spectrum.SpectrumInterval;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.snets2.verification.QoTAwareModulationSelectionTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** KSPXT (port of KSPXT of SNetS v1, #36): the feasible candidate of lowest alpha1 XT/XT_th + alpha2 utilization. */
class KspXtIntegratedRMSCATest {

    /** New circuits: 4 slots at 100 Gbps, tolerant to the XT they suffer (-20 dB). */
    private static final ModulationFormat NEW = mod("new", 1E6, 4, -100, -20);

    private static KspXtIntegratedRMSCA kspxt(double alpha1, double alpha2) {
        KspXtIntegratedRMSCA r = new KspXtIntegratedRMSCA();
        r.setRouting(new DijkstraRouting());
        r.setModulationSelection(new DistanceAdaptiveModulationSelection());
        r.setCoreAndSpectrumAssignment(new ColourFitCoreAndSpectrumAssignment());
        r.configure(Map.of("alpha1", alpha1, "alpha2", alpha2));
        return r;
    }

    /**
     * 3 mutually adjacent cores (colours 0, 1, 2), 16 slots, 500 km (XT of one full neighbour hL = -24.9 dB):
     * core 0 occupied on 12..15 (u = 0.25), core 2 on 4..11 (u = 0.5), core 1 empty. colourfit candidates:
     * core 2 medium fit 12..15 (XT hL, u 0.5), core 1 Last-Fit 12..15 (XT hL, u 0), core 0 First-Fit 0..3 (XT 0, u 0.25).
     */
    private static ControlPlane network(StandardIntegratedRMSCA r) {
        ControlPlane cp = chain(1, 500, 3, 16, 0, List.of(NEW), config(true, false, true, true), r);
        cp.establishCircuit(circuit(cp, "a", 0, 0, 12, 15, LENIENT));
        cp.establishCircuit(circuit(cp, "b", 0, 2, 4, 11, LENIENT));
        return cp;
    }

    @Test
    void registryParametersAndValidation() {
        assertInstanceOf(KspXtIntegratedRMSCA.class, AlgorithmFactory.createIntegrated("kspxt"));
        KspXtIntegratedRMSCA r = new KspXtIntegratedRMSCA();
        assertEquals(0.5, r.getAlpha1());
        assertEquals(0.5, r.getAlpha2());
        assertThrows(IllegalArgumentException.class, () -> r.configure(Map.of("alpha1", -1)));
        KShortestPathsRouting ksp = new KShortestPathsRouting();
        ksp.configure(Map.of("k", 5));
        assertThrows(IllegalArgumentException.class, () -> ksp.configure(Map.of("k", 2.5)));
    }

    @Test
    void colourFitProposesOneIntervalPerCoreInDecreasingOrder() {
        ControlPlane cp = network(kspxt(0.5, 0.5));
        List<CoreSpectrumCandidate> c = new ArrayList<>();
        new ColourFitCoreAndSpectrumAssignment().candidates(cp, new Path(cp.getLinks()), 4).forEach(c::add);
        assertEquals(List.of(new CoreSpectrumCandidate(2, new SpectrumInterval(12, 15)),
                new CoreSpectrumCandidate(1, new SpectrumInterval(12, 15)),
                new CoreSpectrumCandidate(0, new SpectrumInterval(0, 3))), c);
    }

    @Test
    void choosesTheCandidateOfLowestCost() {
        // Utilization only: core 1 (u = 0)
        KspXtIntegratedRMSCA byUse = kspxt(0, 1);
        ControlPlane cp = network(byUse);
        assertEquals(1, byUse.allocate(cp, cp.getNode("0"), cp.getNode("1"), 100.0).coreIndices().get(0));

        // Crosstalk only: core 0 (no overlapping neighbour)
        KspXtIntegratedRMSCA byXt = kspxt(1, 0);
        cp = network(byXt);
        assertEquals(0, byXt.allocate(cp, cp.getNode("0"), cp.getNode("1"), 100.0).coreIndices().get(0));

        // v1 weights: core 1 costs 0.5 * 10^((-24.9 + 20) / 10) = 0.16, core 0 costs 0.5 * 0.25 = 0.125
        KspXtIntegratedRMSCA half = kspxt(0.5, 0.5);
        cp = network(half);
        AllocationResult res = half.allocate(cp, cp.getNode("0"), cp.getNode("1"), 100.0);
        assertEquals(0, res.coreIndices().get(0));
        assertEquals(0, res.startSlot());

        // The standard RMSCA with the same candidates takes the first feasible one (core 2)
        StandardIntegratedRMSCA standard = rmsca(new DistanceAdaptiveModulationSelection());
        standard.setCoreAndSpectrumAssignment(new ColourFitCoreAndSpectrumAssignment());
        cp = network(standard);
        assertEquals(2, standard.allocate(cp, cp.getNode("0"), cp.getNode("1"), 100.0).coreIndices().get(0));
    }

    @Test
    void infeasibleCandidatesAreNeverChosen() {
        // A format that tolerates no crosstalk at all: only core 0 (no overlap) is feasible, whatever the weights
        ModulationFormat strict = mod("strict", 1E6, 4, -100, -60);
        KspXtIntegratedRMSCA byUse = kspxt(0, 1);
        ControlPlane cp = chain(1, 500, 3, 16, 0, List.of(strict), config(true, false, true, true), byUse);
        cp.establishCircuit(circuit(cp, "a", 0, 0, 12, 15, LENIENT));
        cp.establishCircuit(circuit(cp, "b", 0, 2, 4, 11, LENIENT));
        AllocationResult res = byUse.allocate(cp, cp.getNode("0"), cp.getNode("1"), 100.0);
        assertFalse(res.isBlocked());
        assertEquals(0, res.coreIndices().get(0));
    }
}
