package com.snets2.verification;

import com.snets2.config.PhysicalLayerConfig;
import com.snets2.metrics.BlockingCause;
import com.snets2.metrics.PhysicalLayerModel;
import com.snets2.model.*;
import com.snets2.rmsca.AlgorithmFactory;
import com.snets2.rmsca.StandardIntegratedRMSCA;
import com.snets2.rmsca.core.FirstFitCoreAssignment;
import com.snets2.rmsca.modulation.DistanceAdaptiveModulationSelection;
import com.snets2.rmsca.modulation.IModulationSelection;
import com.snets2.rmsca.modulation.QoTAwareModulationSelection;
import com.snets2.rmsca.modulation.SlotCalculator;
import com.snets2.rmsca.regenerator.AsSoonAsRequiredRegeneratorAssignment;
import com.snets2.rmsca.routing.DijkstraRouting;
import com.snets2.rmsca.routing.Path;
import com.snets2.rmsca.spectrum.FirstFitSpectrumAssignment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * QoT-aware modulation selection ({@code qot-adaptive}, issue #23): the most efficient format that satisfies the
 * SNR/XT thresholds of the new circuit and of the established circuits, independently of {@code maxRange}.
 */
class QoTAwareModulationSelectionTest {

    private static final double SLOT = 12.5E9;

    /** Formats of the verification campaign with a 1 km reach: any choice beyond 1 km comes from the physical model. */
    static final List<ModulationFormat> SHORT_REACH = List.of(
        mod("4QAM", 1, 4, 5.92, -16.00), mod("8QAM", 1, 8, 9.32, -19.40), mod("16QAM", 1, 16, 12.34, -22.42),
        mod("32QAM", 1, 32, 15.22, -25.30), mod("64QAM", 1, 64, 18.02, -28.10));

    static ModulationFormat mod(String name, double maxReach, double m, double snrDb, double xtDb) {
        return new ModulationFormat(name, maxReach, m, snrDb, xtDb, 32, 0.1);
    }

    /** Lenient format for background circuits: its own thresholds never block anything. */
    static final ModulationFormat LENIENT = mod("lenient", 1E6, 4, -100, 100);

    static PhysicalLayerConfig config(boolean qot, boolean nli, boolean xt, boolean fixedPsd) {
        return new PhysicalLayerConfig(
            qot, qot, true, nli, xt, xt,
            0.0, 0.0, 80.0, 0.2, 0.0013, 1.6E-5, 1.9385E14,
            6.626E-34, 5.0, 16.0, 100.0, 4.0, 0, 1.9385E14, 5.0,
            fixedPsd, SLOT, 1.0E7, 0.01, 0.012, 4.5E-5, 1.0, 0, SLOT);
    }

    static List<Core> cores(int n, int slots) {
        List<Core> cs = new ArrayList<>();
        for (int c = 0; c < n; c++) {
            List<Integer> adj = new ArrayList<>();
            for (int d = 0; d < n; d++) if (d != c) adj.add(d);
            cs.add(new Core(c, adj, slots));
        }
        return cs;
    }

    /** Chain 0 -> 1 -> ... -> hops, one directed link per hop, all cores mutually adjacent. */
    static ControlPlane chain(int hops, double hopKm, int cores, int slots, int regenerators,
                                      List<ModulationFormat> mods, PhysicalLayerConfig cfg, StandardIntegratedRMSCA rmsca) {
        List<Node> nodes = new ArrayList<>();
        for (int i = 0; i <= hops; i++) nodes.add(new Node(String.valueOf(i), 1000, 1000, regenerators));
        List<Link> links = new ArrayList<>();
        for (int i = 0; i < hops; i++) {
            links.add(new Link(String.valueOf(i), String.valueOf(i + 1), hopKm, cores(cores, slots), List.of()));
        }
        return new ControlPlane(new NetworkTopology(nodes, links, mods), rmsca, SLOT, 0, cfg);
    }

    static StandardIntegratedRMSCA rmsca(IModulationSelection modulation) {
        StandardIntegratedRMSCA r = new StandardIntegratedRMSCA();
        r.setRouting(new DijkstraRouting());
        r.setCoreAssignment(new FirstFitCoreAssignment());
        r.setSpectrumAssignment(new FirstFitSpectrumAssignment());
        r.setModulationSelection(modulation);
        return r;
    }

    static Circuit circuit(ControlPlane cp, String id, int link, int core, int s, int e, ModulationFormat m) {
        List<Link> path = List.of(cp.getLinks().get(link));
        return new Circuit(id, cp.getNode(path.get(0).getSourceId()), cp.getNode(path.get(0).getDestinationId()),
                path, List.of(core), s, e, m, 100.0);
    }

    /** Most efficient format that the physical model accepts for a circuit on core 0 starting at slot 0 (oracle). */
    private static String oracle(ControlPlane cp, double bitRate) {
        Path path = new Path(cp.getLinks());
        PhysicalLayerConfig cfg = cp.getPhysicalLayerConfig();
        List<ModulationFormat> byM = new ArrayList<>(cp.getTopology().modulations());
        byM.sort(Comparator.comparingDouble(ModulationFormat::m).reversed());
        for (ModulationFormat m : byM) {
            int n = SlotCalculator.requiredSlots(bitRate, m, cp);
            double snr = PhysicalLayerModel.predictSNR(cp, path, List.of(), 0, 0, n - 1, m, bitRate);
            double xt = PhysicalLayerModel.predictXtRatio(cp, path, List.of(), 0, 0, n - 1);
            if (snr >= m.getSnrThresholdLinear() && (!cfg.activeXT() || xt <= m.getCrosstalkThresholdLinear())) {
                return m.name();
            }
        }
        return null;
    }

    private static double m(String name) {
        return SHORT_REACH.stream().filter(f -> f.name().equals(name)).findFirst().orElseThrow().m();
    }

    @Test
    @DisplayName("Registered as qot-adaptive; the reach is ignored only while the QoT is active")
    void registryAndReachFlag() {
        IModulationSelection sel = AlgorithmFactory.createModulation("qot-adaptive");
        assertInstanceOf(QoTAwareModulationSelection.class, sel);
        ControlPlane on = chain(1, 100, 1, 64, 0, SHORT_REACH, config(true, false, false, false), rmsca(sel));
        ControlPlane off = chain(1, 100, 1, 64, 0, SHORT_REACH, config(false, false, false, false), rmsca(sel));
        assertFalse(sel.enforcesReach(on));
        assertTrue(sel.enforcesReach(off));
        assertTrue(new DistanceAdaptiveModulationSelection().enforcesReach(on));
    }

    @Test
    @DisplayName("Link length: the most efficient format with SNR >= threshold, downgraded monotonically")
    void monotonicDowngradeWithLength() {
        double previousM = Double.MAX_VALUE;
        Set<String> seen = new LinkedHashSet<>();
        boolean blockedAtTheEnd = false;
        for (double km = 100; km <= 200000; km *= 1.25) {
            StandardIntegratedRMSCA r = rmsca(new QoTAwareModulationSelection());
            ControlPlane cp = chain(1, km, 1, 64, 0, SHORT_REACH, config(true, true, false, false), r);
            String expected = oracle(cp, 100.0);
            AllocationResult res = r.allocate(cp, cp.getNode("0"), cp.getNode("1"), 100.0);

            if (expected == null) {
                assertTrue(res.isBlocked(), km + " km");
                assertEquals(BlockingCause.QOT_NEW, res.blockingCause(), "cause of the most robust format at " + km + " km");
                blockedAtTheEnd = true;
                continue;
            }
            assertFalse(blockedAtTheEnd, "blocking must be monotonic in the length");
            assertFalse(res.isBlocked(), km + " km");
            assertEquals(expected, res.modulation().name(), km + " km");
            assertTrue(res.modulation().m() <= previousM, "no upgrade when the link grows (" + km + " km)");
            previousM = res.modulation().m();
            seen.add(expected);

            // selectModulation (isolated circuit bound) agrees on an empty network
            assertEquals(expected, new QoTAwareModulationSelection().selectModulation(cp, new Path(cp.getLinks()), 100.0)
                    .format().name(), km + " km");
            // distance-adaptive with the same 1 km reach finds no format at all
            AllocationResult da = rmsca(new DistanceAdaptiveModulationSelection())
                    .allocate(cp, cp.getNode("0"), cp.getNode("1"), 100.0);
            assertEquals(BlockingCause.NO_PATH, da.blockingCause());
        }
        assertTrue(seen.size() >= 4, "the sweep must cross several formats: " + seen);
        assertTrue(blockedAtTheEnd, "the sweep must reach the point where no format is viable");
    }

    @Test
    @DisplayName("Neighbours: more crosstalk from adjacent cores downgrades the format monotonically")
    void monotonicDowngradeWithNeighbours() {
        // 500 km, fixed PSD: each fully-overlapping neighbour adds hL = -24.9 dB of XT to the new circuit.
        double previousM = Double.MAX_VALUE;
        Set<String> seen = new LinkedHashSet<>();
        for (int k = 0; k <= 8; k++) {
            StandardIntegratedRMSCA r = rmsca(new QoTAwareModulationSelection());
            ControlPlane cp = chain(1, 500, k + 1, 16, 0, SHORT_REACH, config(true, false, true, true), r);
            for (int c = 1; c <= k; c++) cp.establishCircuit(circuit(cp, "n" + c, 0, c, 0, 15, LENIENT)); // only core 0 free
            String expected = oracle(cp, 100.0);
            AllocationResult res = r.allocate(cp, cp.getNode("0"), cp.getNode("1"), 100.0);
            if (expected == null) {
                assertTrue(res.isBlocked());
                assertEquals(BlockingCause.CROSSTALK, res.blockingCause(), k + " neighbours");
                continue;
            }
            assertEquals(expected, res.modulation().name(), k + " neighbours");
            assertEquals(0, res.coreIndices().get(0));
            assertTrue(res.modulation().m() <= previousM, "no upgrade with more neighbours (" + k + ")");
            previousM = res.modulation().m();
            seen.add(expected);
        }
        assertTrue(seen.size() >= 3, "the sweep must cross several formats: " + seen);
        assertEquals(m("64QAM"), m(seen.iterator().next()), "without neighbours the most efficient format is used");
    }

    @Test
    @DisplayName("A format feasible for the new circuit but violating an established one is rejected")
    void establishedCircuitsAreProtected() {
        // 100 Gbps, 1 polarization, no FEC: 4QAM needs 4 slots, BPSK 8. With the same launch power (variable PSD),
        // a 4QAM candidate on slots 0..3 of core 0 injects XT = hL = -31.9 dB into the victim (core 1, slots 0..3);
        // a BPSK candidate spreads the same power over 8 slots and injects hL/2 = -34.9 dB. Victim threshold: -33 dB.
        ModulationFormat qam4 = mod("4QAM", 1, 4, 0, 0);
        ModulationFormat bpsk = mod("BPSK", 1, 2, 0, 0);
        ModulationFormat strict = mod("strict", 1E6, 4, 0, -33);
        PhysicalLayerConfig cfg = config(true, false, true, false);

        // Premise: 4QAM alone is feasible for the new circuit but violates the victim
        StandardIntegratedRMSCA only4 = rmsca(new QoTAwareModulationSelection());
        ControlPlane p = chain(1, 100, 2, 16, 0, List.of(qam4), cfg, only4);
        p.establishCircuit(circuit(p, "victim", 0, 1, 0, 3, strict));
        p.establishCircuit(circuit(p, "filler", 0, 1, 4, 15, LENIENT));
        AllocationResult premise = only4.allocate(p, p.getNode("0"), p.getNode("1"), 100.0);
        assertTrue(premise.isBlocked());
        assertEquals(BlockingCause.XT_OTHERS, premise.blockingCause());

        // More robust format chosen: core 1 is full, so the only way to protect the victim is BPSK on core 0
        StandardIntegratedRMSCA r = rmsca(new QoTAwareModulationSelection());
        ControlPlane cp = chain(1, 100, 2, 16, 0, List.of(qam4, bpsk), cfg, r);
        Circuit victim = circuit(cp, "victim", 0, 1, 0, 3, strict);
        cp.establishCircuit(victim);
        cp.establishCircuit(circuit(cp, "filler", 0, 1, 4, 15, LENIENT));
        AllocationResult res = r.allocate(cp, cp.getNode("0"), cp.getNode("1"), 100.0);
        assertFalse(res.isBlocked());
        assertEquals("BPSK", res.modulation().name());
        assertEquals(0, res.coreIndices().get(0));
        cp.establishCircuit(res.toCircuit("new"));
        double victimXt = PhysicalLayerModel.predictXtRatio(cp, new Path(victim.getPath()), 1, 0, 3);
        assertTrue(victimXt <= strict.getCrosstalkThresholdLinear(), "victim XT " + 10 * Math.log10(victimXt) + " dB");

        // Other resource: with free slots in core 1 the efficient format is kept, away from the victim's slots
        StandardIntegratedRMSCA r2 = rmsca(new QoTAwareModulationSelection());
        ControlPlane cp2 = chain(1, 100, 2, 16, 0, List.of(qam4, bpsk), cfg, r2);
        cp2.establishCircuit(circuit(cp2, "victim", 0, 1, 0, 3, strict));
        AllocationResult res2 = r2.allocate(cp2, cp2.getNode("0"), cp2.getNode("1"), 100.0);
        assertFalse(res2.isBlocked());
        assertEquals("4QAM", res2.modulation().name(), "format-first: all cores are tried before downgrading");
        assertEquals(1, res2.coreIndices().get(0));
    }

    @Test
    @DisplayName("Regenerators are placed by the SNR of the segments, not by maxRange")
    void regeneratorsPlacedByQoT() {
        // SNR threshold between the SNR of the whole 2-hop path and the SNR of a single hop.
        ControlPlane probe = chain(2, 1500, 1, 64, 5, List.of(mod("x", 1, 16, 0, 0)), config(true, true, false, false),
                rmsca(new QoTAwareModulationSelection()));
        int n = SlotCalculator.requiredSlots(100.0, probe.getTopology().modulations().get(0), probe);
        ModulationFormat x = probe.getTopology().modulations().get(0);
        double full = PhysicalLayerModel.predictSNR(probe, new Path(probe.getLinks()), List.of(), 0, 0, n - 1, x, 100.0);
        double hop = PhysicalLayerModel.predictSNR(probe, new Path(probe.getLinks().subList(0, 1)), List.of(), 0, 0, n - 1, x, 100.0);
        double thresholdDb = 5 * Math.log10(full * hop);  // geometric midpoint, in dB
        ModulationFormat only = mod("16QAM", 1, 16, thresholdDb, 0);

        StandardIntegratedRMSCA r = rmsca(new QoTAwareModulationSelection());
        r.setRegeneratorAssignment(new AsSoonAsRequiredRegeneratorAssignment());
        ControlPlane cp = chain(2, 1500, 1, 64, 5, List.of(only), config(true, true, false, false), r);
        AllocationResult res = r.allocate(cp, cp.getNode("0"), cp.getNode("2"), 100.0);
        assertFalse(res.isBlocked(), "the 1 km reach must not block the regenerated candidate");
        assertEquals(List.of(cp.getNode("1")), res.regeneratorNodes());
    }

    @Test
    @DisplayName("L9-k invariant: after every setup no active circuit is below its SNR or above its XT threshold")
    void noActiveCircuitViolatesItsThresholds() {
        StandardIntegratedRMSCA r = rmsca(new QoTAwareModulationSelection());
        PhysicalLayerConfig cfg = config(true, true, true, false);
        // 5-node ring with a chord, both directions, 7 cores mutually adjacent (worst case for XT), 48 slots
        int[][] edges = { {0, 1, 400}, {1, 2, 900}, {2, 3, 600}, {3, 4, 1500}, {4, 0, 700}, {0, 2, 1200} };
        List<Node> nodes = new ArrayList<>();
        for (int i = 0; i < 5; i++) nodes.add(new Node(String.valueOf(i), 1000, 1000, 0));
        List<Link> links = new ArrayList<>();
        for (int[] e : edges) {
            links.add(new Link(String.valueOf(e[0]), String.valueOf(e[1]), e[2], cores(7, 48), List.of()));
            links.add(new Link(String.valueOf(e[1]), String.valueOf(e[0]), e[2], cores(7, 48), List.of()));
        }
        ControlPlane cp = new ControlPlane(new NetworkTopology(nodes, links, SHORT_REACH), r, SLOT, 0, cfg);

        Random rnd = new Random(23);
        List<String> active = new ArrayList<>();
        Map<String, Integer> formats = new TreeMap<>();
        Map<BlockingCause, Integer> causes = new EnumMap<>(BlockingCause.class);
        double[] rates = {100, 200, 400};
        for (int step = 0; step < 4000; step++) {
            if (!active.isEmpty() && rnd.nextDouble() < 0.45) {
                cp.teardownCircuit(active.remove(rnd.nextInt(active.size())));
                continue;
            }
            int s = rnd.nextInt(5), d = rnd.nextInt(4);
            if (d >= s) d++;
            AllocationResult res = r.allocate(cp, cp.getNode(String.valueOf(s)), cp.getNode(String.valueOf(d)),
                    rates[rnd.nextInt(rates.length)]);
            if (res.isBlocked()) {
                causes.merge(res.blockingCause(), 1, Integer::sum);
                continue;
            }
            String id = "c" + step;
            cp.establishCircuit(res.toCircuit(id));
            active.add(id);
            formats.merge(res.modulation().name(), 1, Integer::sum);

            for (Circuit c : cp.getActiveCircuitsView()) {
                Path path = new Path(c.getPath());
                int core = c.getCoreIndices().get(0);
                double snr = PhysicalLayerModel.predictSNR(cp, path, c.getRegeneratorNodes(), core,
                        c.getStartSlot(), c.getEndSlot(), c.getModulation(), c.getBitRate());
                double xt = PhysicalLayerModel.predictXtRatio(cp, path, c.getRegeneratorNodes(), core, c.getStartSlot(), c.getEndSlot());
                assertTrue(snr >= c.getModulation().getSnrThresholdLinear() * (1 - 1E-9),
                        "step " + step + ": " + c.getId() + " SNR " + 10 * Math.log10(snr) + " dB < " + c.getModulation().snrThreshold());
                assertTrue(xt <= c.getModulation().getCrosstalkThresholdLinear() * (1 + 1E-9),
                        "step " + step + ": " + c.getId() + " XT " + 10 * Math.log10(xt) + " dB > " + c.getModulation().crosstalkThreshold());
            }
        }
        assertTrue(formats.size() >= 3, "several formats must be used: " + formats);
        assertTrue(causes.keySet().stream().anyMatch(c -> c != BlockingCause.FRAGMENTATION),
                "the scenario must exercise the QoT constraints: " + causes);
    }
}
