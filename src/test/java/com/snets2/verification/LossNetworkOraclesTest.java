package com.snets2.verification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.snets2.ExperimentalPlanner;
import com.snets2.config.ConfigLoader;
import com.snets2.config.ScenarioSetup;
import com.snets2.engine.SimulationEngine;
import com.snets2.metrics.BitRateBlockingMetrics;
import com.snets2.output.SimulationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fast regression versions of the loss-system experiments of the V&amp;V campaign
 * (docs/review/04_relatorio_verificacao_validacao.md, E2 to E4), each against an exact oracle.
 */
class LossNetworkOraclesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int REPLICATIONS = 5;

    // ---------------------------------------------------------------------------------------------
    // Scenario helpers
    // ---------------------------------------------------------------------------------------------

    /**
     * Scenario with QoT disabled, no FEC, one polarization and no guard band: R Gbps occupies R / 25 slots
     * in 4QAM. Links are given as {source, destination} pairs, each creating two opposite directed links.
     */
    private static ScenarioSetup scenario(int nodes, int tx, String[][] links, int slots, double load, int requests,
                                          String spectrumAssignment, double... bitRatesAndWeights) throws Exception {
        List<Map<String, Object>> nodeList = new ArrayList<>();
        for (int i = 0; i < nodes; i++) {
            nodeList.add(Map.of("id", String.valueOf(i), "tx", tx, "rx", 1_000_000, "regenerators", 0));
        }
        List<Map<String, Object>> linkList = new ArrayList<>();
        for (String[] l : links) {
            linkList.add(Map.of("source", l[0], "destination", l[1], "length", 100.0));
            linkList.add(Map.of("source", l[1], "destination", l[0], "length", 100.0));
        }
        List<Map<String, Object>> bitRates = new ArrayList<>();
        for (int i = 0; i < bitRatesAndWeights.length; i += 2) {
            bitRates.add(Map.of("value", bitRatesAndWeights[i], "weight", bitRatesAndWeights[i + 1]));
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("networkTopology", Map.of("nodes", nodeList, "links", linkList,
                "cores", List.of(Map.of("id", 0, "adjacentCores", List.of())),
                "modulations", List.of(Map.of("name", "4QAM", "maxRange", 5000.0, "M", 4.0, "SNR", 5.92, "XT", -16.0))));
        root.put("physicalLayer", Map.of("activeQoT", false, "activeQoTForOther", false, "guardBand", 0,
                "bvtSpectralWidth", 12.5E9, "spanLength", 80.0, "polarizationModes", 1.0, "rateOfFEC", 0.0));
        root.put("simulation", Map.of("requests", requests, "warmUpRequests", 5_000, "totalSlots", slots,
                "routing", "djk", "spectrumAssignment", spectrumAssignment,
                "coreAndSpectrumAssignment", "firstfitcore", "integratedRMSCA", "standard",
                "modulationSelection", "fixed"));
        root.put("traffic", Map.of("loadDistributionPerPair", "uniform", "load", load, "bitRates", bitRates));
        root.put("experimentalPlanning", Map.of("replications", 1));
        return ConfigLoader.load(MAPPER.writeValueAsString(root)).getBaseScenario();
    }

    private record Run(SimulationEngine engine, SimulationResult result) {
        BitRateBlockingMetrics blocking() { return engine.getMetricsManager().getBitRateBlocking(); }

        /** Blocking of an ordered pair, whatever other dimension keys the row carries. */
        double pairBlocking(String src, String dest) {
            return result.getData().get("BlockingProbability").values().stream()
                    .filter(r -> r.getSubMetric().equals("BP per pair")
                            && src.equals(r.getDimensions().get("src")) && dest.equals(r.getDimensions().get("dest")))
                    .mapToDouble(r -> r.getRepValues().values().iterator().next())
                    .findFirst().orElseThrow();
        }
    }

    private static List<Run> replicate(ScenarioSetup setup) {
        List<Run> runs = new ArrayList<>();
        for (int seed = 0; seed < REPLICATIONS; seed++) {
            SimulationResult result = new SimulationResult(1);
            runs.add(new Run(ExperimentalPlanner.runReplication(setup, seed, result, Map.of()), result));
        }
        return runs;
    }

    private record Stats(double mean, double se) {
        static Stats of(double[] v) {
            double mean = Arrays.stream(v).average().orElseThrow();
            double var = Arrays.stream(v).map(x -> (x - mean) * (x - mean)).sum() / (v.length - 1);
            return new Stats(mean, Math.sqrt(var / v.length));
        }

        /** Criterion of the verification plan with a small absolute floor for the short runs. */
        void assertMatches(double theory, String what) {
            assertEquals(theory, mean, 4 * se + 0.003, what + ": expected " + theory + ", got " + mean + " ± " + se);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // E2 - transmitters as servers
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("E2: with abundant spectrum, blocking equals Erlang-B(A per node, tx) and is all due to lack of Tx")
    void transmittersBehaveAsErlangServers() throws Exception {
        int tx = 5;
        double a = 2.881; // B(2.881, 5) = 0.1
        ScenarioSetup setup = scenario(2, tx, new String[][] {{"0", "1"}}, 200, 2 * a, 40_000, "firstfit", 25, 1);
        List<Run> runs = replicate(setup);
        Stats.of(runs.stream().mapToDouble(r -> r.blocking().getGeneralBlockingProbability()).toArray())
                .assertMatches(ErlangBSingleLinkTest.erlangB(a, tx), "BP");
        for (Run r : runs) {
            BitRateBlockingMetrics b = r.blocking();
            assertEquals(b.getGeneralBlockingProbability() * b.getGeneralRequestedBitRate(),
                    b.getBitRateBlockingByLackTransmitters(), 1E-9, "every block must be LACK_OF_TRANSMITTERS");
        }
    }

    // ---------------------------------------------------------------------------------------------
    // E3 - two classes on one link: Kaufman-Roberts bound and First-Fit / Last-Fit mirror symmetry
    // ---------------------------------------------------------------------------------------------

    /** Per-class blocking of the multi-rate loss system without contiguity (Kaufman-Roberts recursion). */
    static double[] kaufmanRoberts(double[] loads, int[] sizes, int capacity) {
        double[] q = new double[capacity + 1];
        q[0] = 1;
        for (int j = 1; j <= capacity; j++) {
            for (int k = 0; k < loads.length; k++) {
                if (j - sizes[k] >= 0) q[j] += loads[k] * sizes[k] * q[j - sizes[k]];
            }
            q[j] /= j;
        }
        double total = Arrays.stream(q).sum();
        double[] blocking = new double[sizes.length];
        for (int k = 0; k < sizes.length; k++) {
            for (int j = capacity - sizes[k] + 1; j <= capacity; j++) blocking[k] += q[j] / total;
        }
        return blocking;
    }

    @Test
    @DisplayName("E3: Kaufman-Roberts bounds the wide class and the bandwidth blocking; First-Fit and Last-Fit are mirror images")
    void kaufmanRobertsBoundAndMirrorSymmetry() throws Exception {
        int c = 16;
        double a = 6;
        double[] kr = kaufmanRoberts(new double[] {a / 2, a / 2}, new int[] {1, 2}, c);
        double krBandwidth = (25 * kr[0] + 50 * kr[1]) / 75;

        List<Run> ff = replicate(scenario(2, 1_000_000, new String[][] {{"0", "1"}}, c, 2 * a, 40_000, "firstfit", 25, 1, 50, 1));
        List<Run> lf = replicate(scenario(2, 1_000_000, new String[][] {{"0", "1"}}, c, 2 * a, 40_000, "lastfit", 25, 1, 50, 1));

        Stats wide = Stats.of(ff.stream().mapToDouble(r -> r.blocking().getBlockingProbabilityPerBitRate().get(50.0)).toArray());
        Stats bandwidth = Stats.of(ff.stream().mapToDouble(r -> r.blocking().getGeneralBlockingProbability()).toArray());
        assertTrue(wide.mean() >= kr[1] - 4 * wide.se(), "2-slot class below Kaufman-Roberts: " + wide.mean() + " < " + kr[1]);
        assertTrue(bandwidth.mean() >= krBandwidth - 4 * bandwidth.se(),
                "bandwidth blocking below Kaufman-Roberts: " + bandwidth.mean() + " < " + krBandwidth);

        for (int r = 0; r < REPLICATIONS; r++) {
            assertEquals(ff.get(r).blocking().getBlockingProbabilityPerBitRate(), lf.get(r).blocking().getBlockingProbabilityPerBitRate(),
                    "First-Fit and Last-Fit must follow mirrored, identical trajectories on a single link (seed " + r + ")");
        }
    }

    // ---------------------------------------------------------------------------------------------
    // E4 - three-node line with slot continuity against the exact CTMC
    // ---------------------------------------------------------------------------------------------

    /**
     * Exact blocking of the line 0-1-2 (links L1, L2) with {@code c} slots, first-fit and slot continuity, for one
     * direction. Flows: x uses L1, y uses L2, z uses both on the same slot; each has offered load {@code a}
     * (mu = 1). Slot state: 0 free, 1 x, 2 y, 3 x and y, 4 z. Solved by power iteration on the uniformized chain.
     *
     * @return blocking of x, y and z (PASTA).
     */
    static double[] tandemCtmc(double a, int c) {
        int n = (int) Math.pow(5, c);
        List<int[]> transitions = new ArrayList<>(); // {from, to}
        List<Double> rates = new ArrayList<>();
        boolean[][] blocked = new boolean[3][n];
        int[][] allowed = { {0, 2}, {0, 1}, {0} };
        int[][] move = { {1, -1, 3, -1, -1}, {2, 3, -1, -1, -1}, {4, -1, -1, -1, -1} };
        double[] exitRate = new double[n];
        for (int s = 0; s < n; s++) {
            int[] slots = decode(s, c);
            for (int flow = 0; flow < 3; flow++) {
                int k = firstAllowed(slots, allowed[flow]);
                if (k < 0) {
                    blocked[flow][s] = true;
                } else {
                    int[] t = slots.clone();
                    t[k] = move[flow][slots[k]];
                    transitions.add(new int[] {s, encode(t)});
                    rates.add(a);
                    exitRate[s] += a;
                }
            }
            for (int k = 0; k < c; k++) {
                int[] targets = switch (slots[k]) {
                    case 1, 2, 4 -> new int[] {0};
                    case 3 -> new int[] {2, 1}; // x leaves (y remains) or y leaves (x remains)
                    default -> new int[0];
                };
                for (int to : targets) {
                    int[] t = slots.clone();
                    t[k] = to;
                    transitions.add(new int[] {s, encode(t)});
                    rates.add(1.0);
                    exitRate[s] += 1.0;
                }
            }
        }
        double lambda = Arrays.stream(exitRate).max().orElseThrow() * 1.01;
        double[] pi = new double[n];
        pi[0] = 1.0;
        for (int iter = 0; iter < 200_000; iter++) {
            double[] next = new double[n];
            for (int s = 0; s < n; s++) next[s] = pi[s] * (1 - exitRate[s] / lambda);
            for (int i = 0; i < transitions.size(); i++) {
                int[] tr = transitions.get(i);
                next[tr[1]] += pi[tr[0]] * rates.get(i) / lambda;
            }
            double diff = 0;
            for (int s = 0; s < n; s++) diff += Math.abs(next[s] - pi[s]);
            pi = next;
            if (diff < 1E-13) break;
        }
        double[] result = new double[3];
        for (int flow = 0; flow < 3; flow++) {
            for (int s = 0; s < n; s++) if (blocked[flow][s]) result[flow] += pi[s];
        }
        return result;
    }

    private static int[] decode(int s, int c) {
        int[] slots = new int[c];
        for (int k = 0; k < c; k++) { slots[k] = s % 5; s /= 5; }
        return slots;
    }

    private static int encode(int[] slots) {
        int s = 0;
        for (int k = slots.length - 1; k >= 0; k--) s = s * 5 + slots[k];
        return s;
    }

    private static int firstAllowed(int[] slots, int[] allowed) {
        for (int k = 0; k < slots.length; k++) {
            for (int v : allowed) if (slots[k] == v) return k;
        }
        return -1;
    }

    @Test
    @DisplayName("E4: line of 3 nodes with slot continuity and first-fit matches the exact Markov chain")
    void tandemMatchesExactMarkovChain() throws Exception {
        int c = 4;
        double a = 1.0;
        double[] exact = tandemCtmc(a, c);
        assertEquals(0.07916, exact[0], 1E-4, "CTMC oracle (value of the V&V report)");
        assertEquals(0.1752, exact[2], 1E-4, "CTMC oracle (value of the V&V report)");

        ScenarioSetup setup = scenario(3, 1_000_000, new String[][] {{"0", "1"}, {"1", "2"}}, c, 6 * a, 60_000, "firstfit", 25, 1);
        List<Run> runs = replicate(setup);
        // both directions are statistically identical: average the equivalent pairs
        Stats.of(runs.stream().mapToDouble(r -> (r.pairBlocking("0", "1") + r.pairBlocking("2", "1")) / 2).toArray())
                .assertMatches(exact[0], "1 hop, first link");
        Stats.of(runs.stream().mapToDouble(r -> (r.pairBlocking("1", "2") + r.pairBlocking("1", "0")) / 2).toArray())
                .assertMatches(exact[1], "1 hop, second link");
        Stats.of(runs.stream().mapToDouble(r -> (r.pairBlocking("0", "2") + r.pairBlocking("2", "0")) / 2).toArray())
                .assertMatches(exact[2], "2 hops");
    }
}
