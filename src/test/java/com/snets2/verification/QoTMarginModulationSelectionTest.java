package com.snets2.verification;

import com.snets2.config.PhysicalLayerConfig;
import com.snets2.metrics.PhysicalLayerModel;
import com.snets2.model.*;
import com.snets2.rmsca.AlgorithmFactory;
import com.snets2.rmsca.StandardIntegratedRMSCA;
import com.snets2.rmsca.modulation.IModulationSelection;
import com.snets2.rmsca.modulation.QoTAwareModulationSelection;
import com.snets2.rmsca.modulation.QoTMarginModulationSelection;
import com.snets2.rmsca.modulation.SlotCalculator;
import com.snets2.rmsca.routing.KShortestPathsRouting;
import com.snets2.rmsca.routing.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static com.snets2.verification.QoTAwareModulationSelectionTest.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * QoT-aware modulation selection with an SNR/XT margin ({@code qot-margin}, issue #31): on each path, the most
 * efficient candidate whose new circuit keeps the margin, otherwise the {@code qot-adaptive} choice.
 */
class QoTMarginModulationSelectionTest {

    /** Most efficient format on core 0, slots from 0, keeping the margins (dB); null if none keeps them. */
    private static String oracle(ControlPlane cp, double bitRate, double sigmaDb, double sigmaXtDb) {
        Path path = new Path(cp.getLinks());
        PhysicalLayerConfig cfg = cp.getPhysicalLayerConfig();
        List<ModulationFormat> byM = new ArrayList<>(cp.getTopology().modulations());
        byM.sort(Comparator.comparingDouble(ModulationFormat::m).reversed());
        for (ModulationFormat m : byM) {
            int n = SlotCalculator.requiredSlots(bitRate, m, cp);
            double snr = PhysicalLayerModel.predictSNR(cp, path, List.of(), 0, 0, n - 1, m, bitRate);
            double xt = PhysicalLayerModel.predictXtRatio(cp, path, List.of(), 0, 0, n - 1);
            if (snr >= m.getSnrThresholdLinear() * Math.pow(10, sigmaDb / 10)
                    && (!cfg.activeXT() || xt <= m.getCrosstalkThresholdLinear() / Math.pow(10, sigmaXtDb / 10))) {
                return m.name();
            }
        }
        return null;
    }

    @Test
    @DisplayName("Registered as qot-margin; parameters sigma and sigmaXt; no margin without QoT")
    void registryAndParameters() {
        IModulationSelection sel = AlgorithmFactory.createModulation("qot-margin");
        assertInstanceOf(QoTMarginModulationSelection.class, sel);
        QoTMarginModulationSelection margin = (QoTMarginModulationSelection) sel;
        assertEquals(Set.of("sigma", "sigmaXt"), margin.parameterNames());
        margin.configure(Map.of("sigma", "1.5", "sigmaXt", 2));
        ControlPlane on = chain(1, 100, 1, 64, 0, SHORT_REACH, config(true, false, false, false), rmsca(sel));
        ControlPlane off = chain(1, 100, 1, 64, 0, SHORT_REACH, config(false, false, false, false), rmsca(sel));
        assertEquals(1.5, sel.snrMarginDb(on));
        assertEquals(2.0, sel.xtMarginDb(on));
        assertEquals(0.0, sel.snrMarginDb(off));
        assertEquals(0.0, sel.xtMarginDb(off));
        assertThrows(IllegalArgumentException.class, () -> margin.configure(Map.of("sigma", -0.1)));
        assertEquals(0.0, new QoTAwareModulationSelection().snrMarginDb(on), "qot-adaptive has no margin");
    }

    @Test
    @DisplayName("SNR margin: the most efficient format keeping sigma, else the most efficient feasible one")
    void snrMarginSweep() {
        // A link where the best feasible format is not the most robust one, so that a margin can downgrade it
        double km = 100;
        ControlPlane probe;
        do {
            km *= 1.25;
            probe = chain(1, km, 1, 64, 0, SHORT_REACH, config(true, true, false, false), rmsca(new QoTAwareModulationSelection()));
        } while (!"16QAM".equals(oracle(probe, 100.0, 0, 0)));

        String feasible = oracle(probe, 100.0, 0, 0);
        double previousM = Double.MAX_VALUE;
        boolean fallbackSeen = false;
        Set<String> seen = new LinkedHashSet<>();
        for (double sigma = 0; sigma <= 20; sigma += 0.5) {
            StandardIntegratedRMSCA r = rmsca(new QoTMarginModulationSelection(sigma, 0));
            ControlPlane cp = chain(1, km, 1, 64, 0, SHORT_REACH, config(true, true, false, false), r);
            AllocationResult res = r.allocate(cp, cp.getNode("0"), cp.getNode("1"), 100.0);
            assertFalse(res.isBlocked(), "a margin never blocks (sigma " + sigma + ")");
            String keeping = oracle(cp, 100.0, sigma, 0);
            if (keeping == null) {
                assertEquals(feasible, res.modulation().name(), "no format keeps " + sigma + " dB: qot-adaptive choice");
                fallbackSeen = true;
                continue;
            }
            assertFalse(fallbackSeen, "once no format keeps the margin, a larger margin cannot be kept either");
            assertEquals(keeping, res.modulation().name(), "sigma " + sigma);
            assertTrue(res.modulation().m() <= previousM, "no upgrade when sigma grows (" + sigma + " dB)");
            previousM = res.modulation().m();
            seen.add(keeping);
        }
        assertTrue(seen.size() >= 3, "the sweep must cross several formats: " + seen);
        assertTrue(fallbackSeen, "the sweep must reach margins that no format keeps");
    }

    @Test
    @DisplayName("XT margin: neighbours on adjacent cores downgrade the format through sigmaXt")
    void xtMarginSweep() {
        // 500 km, fixed PSD, two fully-overlapping neighbours: XT of the new circuit = 2 hL
        double previousM = Double.MAX_VALUE;
        Set<String> seen = new LinkedHashSet<>();
        for (double sigmaXt = 0; sigmaXt <= 12; sigmaXt += 1) {
            StandardIntegratedRMSCA r = rmsca(new QoTMarginModulationSelection(0, sigmaXt));
            ControlPlane cp = chain(1, 500, 3, 16, 0, SHORT_REACH, config(true, false, true, true), r);
            for (int c = 1; c <= 2; c++) cp.establishCircuit(circuit(cp, "n" + c, 0, c, 0, 15, LENIENT));
            AllocationResult res = r.allocate(cp, cp.getNode("0"), cp.getNode("1"), 100.0);
            String keeping = oracle(cp, 100.0, 0, sigmaXt);
            if (keeping == null) {
                assertEquals(oracle(cp, 100.0, 0, 0), res.modulation().name());
                break;
            }
            assertEquals(keeping, res.modulation().name(), "sigmaXt " + sigmaXt);
            assertTrue(res.modulation().m() <= previousM);
            previousM = res.modulation().m();
            seen.add(keeping);
        }
        assertTrue(seen.size() >= 2, "the XT margin must downgrade the format: " + seen);
    }

    @Test
    @DisplayName("Dynamic network: same blocking as qot-adaptive; same choice when it keeps the margin; invariant L9-k")
    void agreesWithQoTAdaptive() {
        for (double[] margins : new double[][] { {0, 0}, {1.5, 2.0}, {4.0, 0} }) {
            QoTMarginModulationSelection sel = new QoTMarginModulationSelection(margins[0], margins[1]);
            StandardIntegratedRMSCA marginRmsca = rmsca(sel);
            marginRmsca.setRouting(new KShortestPathsRouting(3));
            StandardIntegratedRMSCA adaptive = rmsca(new QoTAwareModulationSelection());
            adaptive.setRouting(new KShortestPathsRouting(3));

            int[][] edges = { {0, 1, 400}, {1, 2, 900}, {2, 3, 600}, {3, 4, 1500}, {4, 0, 700}, {0, 2, 1200} };
            List<Node> nodes = new ArrayList<>();
            for (int i = 0; i < 5; i++) nodes.add(new Node(String.valueOf(i), 1000, 1000, 0));
            List<Link> links = new ArrayList<>();
            for (int[] e : edges) {
                links.add(new Link(String.valueOf(e[0]), String.valueOf(e[1]), e[2], cores(7, 48), List.of()));
                links.add(new Link(String.valueOf(e[1]), String.valueOf(e[0]), e[2], cores(7, 48), List.of()));
            }
            ControlPlane cp = new ControlPlane(new NetworkTopology(nodes, links, SHORT_REACH), marginRmsca, 12.5E9, 0,
                    config(true, true, true, false));
            double snrFactor = Math.pow(10, margins[0] / 10), xtFactor = Math.pow(10, margins[1] / 10);

            Random rnd = new Random(31);
            List<String> active = new ArrayList<>();
            int kept = 0, notKept = 0, differs = 0;
            double[] rates = {100, 200, 400};
            for (int step = 0; step < 2500; step++) {
                if (!active.isEmpty() && rnd.nextDouble() < 0.45) {
                    cp.teardownCircuit(active.remove(rnd.nextInt(active.size())));
                    continue;
                }
                int s = rnd.nextInt(5), d = rnd.nextInt(4);
                if (d >= s) d++;
                double rate = rates[rnd.nextInt(rates.length)];
                Node src = cp.getNode(String.valueOf(s)), dst = cp.getNode(String.valueOf(d));
                AllocationResult a = adaptive.allocate(cp, src, dst, rate);
                AllocationResult b = marginRmsca.allocate(cp, src, dst, rate);

                assertEquals(a.isBlocked(), b.isBlocked(), "a margin never changes the feasibility (step " + step + ")");
                if (b.isBlocked()) {
                    assertEquals(a.blockingCause(), b.blockingCause());
                    continue;
                }
                boolean aKeeps = keepsMargin(cp, a, snrFactor, xtFactor);
                boolean bKeeps = keepsMargin(cp, b, snrFactor, xtFactor);
                if (aKeeps || !bKeeps) assertEquals(a, b, "step " + step);
                if (bKeeps) kept++; else notKept++;
                if (!a.equals(b)) differs++;
                if (margins[0] == 0 && margins[1] == 0) assertEquals(a, b, "sigma = 0 is qot-adaptive");

                String id = "c" + step;
                cp.establishCircuit(b.toCircuit(id));
                active.add(id);
                for (Circuit c : cp.getActiveCircuitsView()) {
                    Path path = new Path(c.getPath());
                    int core = c.getCoreIndices().get(0);
                    double snr = PhysicalLayerModel.predictSNR(cp, path, c.getRegeneratorNodes(), core,
                            c.getStartSlot(), c.getEndSlot(), c.getModulation(), c.getBitRate());
                    double xt = PhysicalLayerModel.predictXtRatio(cp, path, c.getRegeneratorNodes(), core, c.getStartSlot(), c.getEndSlot());
                    assertTrue(snr >= c.getModulation().getSnrThresholdLinear() * (1 - 1E-9), "step " + step + ": " + c.getId());
                    assertTrue(xt <= c.getModulation().getCrosstalkThresholdLinear() * (1 + 1E-9), "step " + step + ": " + c.getId());
                }
            }
            if (margins[0] > 0) {
                assertTrue(differs > 0, "the margin must change some choices " + Arrays.toString(margins));
                assertTrue(kept > 0 && notKept > 0, "both branches must be exercised: kept " + kept + ", not kept " + notKept);
            }
        }
    }

    /** Whether the new circuit of {@code res} keeps the margins, evaluated before it is established. */
    private static boolean keepsMargin(ControlPlane cp, AllocationResult res, double snrFactor, double xtFactor) {
        Path path = new Path(res.path());
        int core = res.coreIndices().get(0);
        ModulationFormat m = res.modulation();
        double snr = PhysicalLayerModel.predictSNR(cp, path, res.regeneratorNodes(), core, res.startSlot(), res.endSlot(), m, res.bitRate());
        double xt = PhysicalLayerModel.predictXtRatio(cp, path, res.regeneratorNodes(), core, res.startSlot(), res.endSlot());
        return snr >= m.getSnrThresholdLinear() * snrFactor && xt <= m.getCrosstalkThresholdLinear() / xtFactor;
    }
}
