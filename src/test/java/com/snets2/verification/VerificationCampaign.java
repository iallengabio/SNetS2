package com.snets2.verification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.snets2.ExperimentalPlanner;
import com.snets2.config.ConfigLoader;
import com.snets2.config.ConfigValidator;
import com.snets2.config.PhysicalLayerConfig;
import com.snets2.config.ScenarioSetup;
import com.snets2.config.TopologyMapper;
import com.snets2.engine.SimulationEngine;
import com.snets2.metrics.BitRateBlockingMetrics;
import com.snets2.metrics.EnergyConsumptionModel;
import com.snets2.metrics.PhysicalLayerModel;
import com.snets2.model.*;
import com.snets2.output.SimulationResult;
import com.snets2.rmsca.routing.Path;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

/**
 * Verification and validation campaign of SNetS2 (report: docs/review/04_relatorio_verificacao_validacao.md,
 * plan: docs/review/03_plano_de_verificacao.md).
 *
 * <p>Runs the simulator through {@link ExperimentalPlanner#runReplication} (the production path) and the
 * physical layer model directly, and writes one CSV per experiment with the raw value of every replication.
 * The analytical oracles, statistics and figures are produced by {@code scripts/verification/analyze.py}.</p>
 *
 * <pre>
 * ./build.sh &amp;&amp; mvn -q test-compile
 * java -cp target/SNetS2-1.0-SNAPSHOT.jar:target/test-classes com.snets2.verification.VerificationCampaign \
 *      docs/review/vv/data [experiment ...]
 * </pre>
 *
 * Experiments: erlang, tx, kr, tandem, algos, energy, phy, qotnet (all when none is given).
 */
public final class VerificationCampaign {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int THREADS = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
    private static final double SLOT = 12.5E9;

    private VerificationCampaign() {}

    public static void main(String[] args) throws Exception {
        File out = new File(args.length > 0 ? args[0] : "docs/review/vv/data");
        out.mkdirs();
        Set<String> selected = new LinkedHashSet<>(Arrays.asList(args).subList(Math.min(1, args.length), args.length));
        Map<String, Callable<Void>> experiments = new LinkedHashMap<>();
        experiments.put("erlang", () -> { erlang(out); return null; });
        experiments.put("tx", () -> { transmitters(out); return null; });
        experiments.put("kr", () -> { kaufmanRoberts(out); return null; });
        experiments.put("tandem", () -> { tandem(out); return null; });
        experiments.put("algos", () -> { algorithms(out); return null; });
        experiments.put("energy", () -> { energy(out); return null; });
        experiments.put("phy", () -> { physicalLayer(out); return null; });
        experiments.put("qotnet", () -> { qotNetwork(out); return null; });
        for (Map.Entry<String, Callable<Void>> e : experiments.entrySet()) {
            if (!selected.isEmpty() && !selected.contains(e.getKey())) continue;
            long t0 = System.nanoTime();
            System.out.println("== " + e.getKey());
            e.getValue().call();
            System.out.printf("   done in %.1f s%n", (System.nanoTime() - t0) / 1E9);
        }
        POOL.shutdown();
    }

    // =====================================================================================
    // Scenario construction
    // =====================================================================================

    /** Mutable JSON tree of an experiment setup, validated and parsed by the production loader. */
    static final class Scenario {
        final List<Map<String, Object>> nodes = new ArrayList<>();
        final List<Map<String, Object>> links = new ArrayList<>();
        List<Map<String, Object>> cores = List.of(core(0));
        List<Map<String, Object>> modulations = List.of(MODULATIONS.get(0));
        final Map<String, Object> physical = defaultPhysical();
        final Map<String, Object> simulation = new LinkedHashMap<>();
        final Map<String, Object> traffic = new LinkedHashMap<>();

        Scenario() {
            simulation.put("requests", 100_000);
            simulation.put("warmUpRequests", 10_000);
            simulation.put("totalSlots", 320);
            simulation.put("routing", "djk");
            simulation.put("spectrumAssignment", "firstfit");
            simulation.put("coreAndSpectrumAssignment", "firstfitcore");
            simulation.put("integratedRMSCA", "standard");
            simulation.put("modulationSelection", "fixed");
            Map<String, Boolean> metrics = new LinkedHashMap<>();
            for (String m : ConfigValidator.KNOWN_METRICS) metrics.put(m, false);
            metrics.put("BlockingProbability", true);
            metrics.put("SpectrumUtilization", true);
            simulation.put("activeMetrics", metrics);
            traffic.put("loadDistributionPerPair", "uniform");
            traffic.put("load", 10.0);
            traffic.put("bitRates", List.of(Map.of("value", 25.0, "weight", 1.0)));
        }

        Scenario node(String id, int tx, int rx, int regenerators) {
            nodes.add(Map.of("id", id, "tx", tx, "rx", rx, "regenerators", regenerators));
            return this;
        }

        /** Node with an explicit add/drop degree (term a of the OXC power, energy model). */
        Scenario node(String id, int tx, int rx, int regenerators, int addDropDegree) {
            nodes.add(Map.of("id", id, "tx", tx, "rx", rx, "regenerators", regenerators, "addDropDegree", addDropDegree));
            return this;
        }

        /** Two opposite directed links. */
        Scenario link(String a, String b, double km) {
            links.add(Map.of("source", a, "destination", b, "length", km));
            links.add(Map.of("source", b, "destination", a, "length", km));
            return this;
        }

        Scenario sim(String key, Object value) { simulation.put(key, value); return this; }
        Scenario phy(String key, Object value) { physical.put(key, value); return this; }
        Scenario load(double load) { traffic.put("load", load); return this; }

        @SuppressWarnings("unchecked")
        Scenario metric(String name) {
            ((Map<String, Boolean>) simulation.get("activeMetrics")).put(name, true);
            return this;
        }

        Scenario bitRates(double... valuesAndWeights) {
            List<Map<String, Object>> list = new ArrayList<>();
            for (int i = 0; i < valuesAndWeights.length; i += 2) {
                list.add(Map.of("value", valuesAndWeights[i], "weight", valuesAndWeights[i + 1]));
            }
            traffic.put("bitRates", list);
            return this;
        }

        /** QoT disabled, no FEC, one polarization, no guard band: R Gbps occupies R / 25 slots in 4QAM. */
        Scenario idealSlots() {
            phy("activeQoT", false).phy("activeQoTForOther", false);
            return phy("rateOfFEC", 0.0).phy("polarizationModes", 1.0).phy("guardBand", 0);
        }

        ScenarioSetup setup() throws IOException {
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("networkTopology", Map.of("nodes", nodes, "links", links, "cores", cores, "modulations", modulations));
            root.put("physicalLayer", physical);
            root.put("simulation", simulation);
            root.put("traffic", traffic);
            root.put("experimentalPlanning", Map.of("replications", 1));
            ScenarioSetup setup = ConfigLoader.load(MAPPER.writeValueAsString(root)).getBaseScenario();
            ConfigValidator.validate(setup);
            return setup;
        }
    }

    /** Physical parameters of the experiments shipped in the repository (experiments/experiment01). */
    static Map<String, Object> defaultPhysical() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("activeQoT", true); p.put("activeQoTForOther", true);
        p.put("activeASE", true); p.put("activeNLI", true);
        p.put("activeXT", true); p.put("activeXTForOther", true);
        p.put("rateOfFEC", 0.25); p.put("power", 0.0);
        p.put("spanLength", 80.0); p.put("fiberLoss", 0.2);
        p.put("fiberNonlinearity", 0.0013); p.put("fiberDispersion", 1.6E-5);
        p.put("centerFrequency", 1.9385E14); p.put("constantOfPlanck", 6.626E-34);
        p.put("noiseFigureOfOpticalAmplifier", 5.0); p.put("powerSaturationOfOpticalAmplifier", 23.0);
        p.put("noiseFactorModelParameterA1", 100.0); p.put("noiseFactorModelParameterA2", 4.0);
        p.put("typeOfAmplifierGain", 0); p.put("amplificationFrequency", 1.9385E14);
        p.put("switchInsertionLoss", 5.0); p.put("fixedPowerSpectralDensity", false);
        p.put("referenceBandwidthForPowerSpectralDensity", 12.5E9);
        p.put("propagationConstant", 1.0E7); p.put("bendingRadius", 0.01);
        p.put("couplingCoefficient", 0.012); p.put("corePitch", 4.5E-5);
        p.put("polarizationModes", 2.0); p.put("guardBand", 1); p.put("bvtSpectralWidth", SLOT);
        return p;
    }

    /** Modulation formats of the shipped experiments (pre-FEC thresholds for a 25 % SD-FEC; maxRange from scripts/compute_reach.sh). */
    static final List<Map<String, Object>> MODULATIONS = List.of(
        modulation("4QAM", 5110, 4, 5.92, -16.00),
        modulation("8QAM", 3270, 8, 9.32, -19.40),
        modulation("16QAM", 2000, 16, 12.34, -22.42),
        modulation("32QAM", 400, 32, 15.22, -25.30),
        modulation("64QAM", 160, 64, 18.02, -28.10));

    static Map<String, Object> modulation(String name, double maxRange, double m, double snr, double xt) {
        return Map.of("name", name, "maxRange", maxRange, "M", m, "SNR", snr, "XT", xt);
    }

    static Map<String, Object> core(int id, Integer... adjacent) {
        return Map.of("id", id, "adjacentCores", List.of(adjacent));
    }

    /** 7-core hexagonal MCF: core 0 in the centre, cores 1..6 on the ring. */
    static List<Map<String, Object>> hexagonal7() {
        return List.of(core(0, 1, 2, 3, 4, 5, 6), core(1, 0, 2, 6), core(2, 0, 1, 3), core(3, 0, 2, 4),
                core(4, 0, 3, 5), core(5, 0, 4, 6), core(6, 0, 1, 5));
    }

    static Scenario twoNode(double km) {
        return new Scenario().node("0", 1_000_000, 1_000_000, 0).node("1", 1_000_000, 1_000_000, 0).link("0", "1", km);
    }

    /**
     * NSFNET (14 nodes, 21 bidirectional links) with the link lengths commonly used in the elastic
     * optical network literature, multiplied by {@code scale}.
     */
    static Scenario nsfnet(double scale) {
        Scenario s = new Scenario();
        for (int i = 0; i < 14; i++) s.node(String.valueOf(i), 1_000_000, 1_000_000, 0);
        int[][] edges = { {0, 1, 2100}, {0, 2, 3000}, {0, 7, 4800}, {1, 2, 1200}, {1, 3, 1500}, {2, 5, 3600},
                {3, 4, 1200}, {3, 10, 3900}, {4, 5, 2400}, {4, 6, 1200}, {5, 9, 2100}, {5, 13, 3600},
                {6, 7, 1500}, {7, 8, 1500}, {8, 9, 1500}, {8, 11, 600}, {8, 12, 600}, {10, 11, 1200},
                {10, 12, 1500}, {11, 13, 600}, {12, 13, 300} };
        for (int[] e : edges) s.link(String.valueOf(e[0]), String.valueOf(e[1]), e[2] * scale);
        return s;
    }

    // =====================================================================================
    // Execution helpers
    // =====================================================================================

    private static final ExecutorService POOL = Executors.newFixedThreadPool(THREADS);

    /** Result of one replication: the engine (live metrics) and the collected result rows. */
    record Run(SimulationEngine engine, SimulationResult result) {
        BitRateBlockingMetrics blocking() { return engine.getMetricsManager().getBitRateBlocking(); }

        double value(String sheet, String subMetric, Map<String, String> dimensions) {
            SimulationResult.MetricRow row = result.getData().getOrDefault(sheet, Map.of()).values().stream()
                    .filter(r -> r.getSubMetric().equals(subMetric) && r.getDimensions().equals(dimensions))
                    .findFirst().orElse(null);
            return row == null ? Double.NaN : row.getRepValues().getOrDefault(0, Double.NaN);
        }

        double value(String sheet, String subMetric) { return value(sheet, subMetric, Map.of()); }

        double utilization() { return engine.getMetricsManager().getResourceUtilization().getAverageGeneralUtilization(); }
    }

    /** Runs {@code reps} replications (seeds 0..reps-1) in parallel and returns them in seed order. */
    static List<Run> replicate(ScenarioSetup setup, int reps) throws Exception {
        List<Future<Run>> futures = new ArrayList<>();
        for (int r = 0; r < reps; r++) {
            final int seed = r;
            futures.add(POOL.submit(() -> {
                SimulationResult result = new SimulationResult(1);
                // repId 0 in the result so that values are read uniformly; the seed is passed separately
                SimulationEngine engine = ExperimentalPlanner.runReplication(setup, seed, result, Map.of());
                SimulationResult normalized = new SimulationResult(1);
                result.getData().forEach((sheet, rows) -> rows.values().forEach(row -> row.getRepValues().values()
                        .forEach(v -> normalized.addValue(sheet, row.getSubMetric(), row.getDimensions(), Map.of(), 0, v))));
                return new Run(engine, normalized);
            }));
        }
        List<Run> runs = new ArrayList<>();
        for (Future<Run> f : futures) runs.add(f.get());
        return runs;
    }

    /** Small CSV writer. */
    static final class Csv implements AutoCloseable {
        private final PrintWriter writer;

        Csv(File dir, String name, String... header) throws IOException {
            writer = new PrintWriter(new File(dir, name + ".csv"), "UTF-8");
            writer.println(String.join(",", header));
        }

        void row(Object... values) {
            StringJoiner j = new StringJoiner(",");
            for (Object v : values) j.add(String.valueOf(v));
            writer.println(j);
        }

        @Override
        public void close() { writer.close(); }
    }

    /** Erlang-B by the stable recursion; used here only to choose loads, the oracle lives in analyze.py. */
    static double erlangB(double a, int c) {
        double b = 1.0;
        for (int k = 1; k <= c; k++) b = a * b / (k + a * b);
        return b;
    }

    /** Offered load giving the target Erlang-B blocking (bisection). */
    static double loadForBlocking(double target, int c) {
        double lo = 1E-6, hi = 10.0 * c + 10;
        for (int i = 0; i < 200; i++) {
            double mid = 0.5 * (lo + hi);
            if (erlangB(mid, c) < target) lo = mid; else hi = mid;
        }
        return 0.5 * (lo + hi);
    }

    // =====================================================================================
    // E1 - Erlang B (L2-a/b)
    // =====================================================================================

    static void erlang(File out) throws Exception {
        try (Csv csv = new Csv(out, "e1_erlang", "slots", "load_per_direction", "rep", "bp", "utilization")) {
            for (int c : new int[] {1, 5, 20, 80}) {
                for (double target : new double[] {0.001, 0.01, 0.05, 0.1, 0.2, 0.4}) {
                    double a = loadForBlocking(target, c);
                    ScenarioSetup setup = twoNode(100).idealSlots()
                            .sim("totalSlots", c).sim("requests", 220_000).sim("warmUpRequests", 20_000)
                            .load(2 * a).setup();
                    List<Run> runs = replicate(setup, 10);
                    for (int r = 0; r < runs.size(); r++) {
                        csv.row(c, a, r, runs.get(r).blocking().getGeneralBlockingProbability(), runs.get(r).utilization());
                    }
                }
            }
        }
    }

    // =====================================================================================
    // E2 - Transmitters as servers (L3-a)
    // =====================================================================================

    static void transmitters(File out) throws Exception {
        try (Csv csv = new Csv(out, "e2_transmitters", "tx", "load_per_node", "rep", "bp", "bp_lack_tx", "bp_other_causes")) {
            for (int tx : new int[] {5, 20}) {
                for (double target : new double[] {0.01, 0.05, 0.1, 0.2, 0.4}) {
                    double a = loadForBlocking(target, tx);
                    Scenario s = new Scenario().node("0", tx, 1_000_000, 0).node("1", tx, 1_000_000, 0).link("0", "1", 100);
                    ScenarioSetup setup = s.idealSlots().sim("totalSlots", 2000)
                            .sim("requests", 120_000).sim("warmUpRequests", 20_000).load(2 * a).setup();
                    List<Run> runs = replicate(setup, 10);
                    for (int r = 0; r < runs.size(); r++) {
                        BitRateBlockingMetrics b = runs.get(r).blocking();
                        double req = b.getGeneralRequestedBitRate();
                        double lackTx = b.getBitRateBlockingByLackTransmitters() / req;
                        csv.row(tx, a, r, b.getGeneralBlockingProbability(), lackTx, b.getGeneralBlockingProbability() - lackTx);
                    }
                }
            }
        }
    }

    // =====================================================================================
    // E3 - Two classes on one link: Kaufman-Roberts and spectrum assignment policies (L4-a/b)
    // =====================================================================================

    static void kaufmanRoberts(File out) throws Exception {
        try (Csv csv = new Csv(out, "e3_kaufman_roberts", "algorithm", "slots", "load_per_direction", "rep", "bp_1slot", "bp_2slot", "bp_bitrate")) {
            int c = 16;
            for (String algorithm : List.of("firstfit", "lastfit", "exactfit", "randomfit")) {
                for (double a : new double[] {2, 4, 6, 8, 10, 12}) {
                    // 25 Gbps = 1 slot, 50 Gbps = 2 slots, equal request rates
                    ScenarioSetup setup = twoNode(100).idealSlots().bitRates(25, 1, 50, 1)
                            .sim("totalSlots", c).sim("spectrumAssignment", algorithm)
                            .sim("requests", 220_000).sim("warmUpRequests", 20_000).load(2 * a).setup();
                    List<Run> runs = replicate(setup, 10);
                    for (int r = 0; r < runs.size(); r++) {
                        Map<Double, Double> perClass = runs.get(r).blocking().getBlockingProbabilityPerBitRate();
                        csv.row(algorithm, c, a, r, perClass.getOrDefault(25.0, 0.0), perClass.getOrDefault(50.0, 0.0),
                                runs.get(r).blocking().getGeneralBlockingProbability());
                    }
                }
            }
        }
    }

    // =====================================================================================
    // E4 - Three-node line with slot continuity (L6-a): exact CTMC in analyze.py
    // =====================================================================================

    static void tandem(File out) throws Exception {
        try (Csv csv = new Csv(out, "e4_tandem", "slots", "load_per_pair", "rep", "bp_01", "bp_12", "bp_02", "bp_all")) {
            int c = 4;
            for (double a : new double[] {0.5, 1.0, 1.5, 2.0, 3.0, 4.0}) {
                Scenario s = new Scenario().node("0", 1_000_000, 1_000_000, 0).node("1", 1_000_000, 1_000_000, 0)
                        .node("2", 1_000_000, 1_000_000, 0).link("0", "1", 100).link("1", "2", 100);
                // six ordered pairs, each with load a
                ScenarioSetup setup = s.idealSlots().sim("totalSlots", c)
                        .sim("requests", 300_000).sim("warmUpRequests", 20_000).load(6 * a).setup();
                List<Run> runs = replicate(setup, 10);
                for (int r = 0; r < runs.size(); r++) {
                    Run run = runs.get(r);
                    Function<String, Double> pair = p -> {
                        String[] n = p.split("-");
                        return run.value("BlockingProbability", "BP per pair", Map.of("src", n[0], "dest", n[1], "core", "all", "bitrate", "all"));
                    };
                    // both directions are statistically identical: average them
                    double bp01 = (pair.apply("0-1") + pair.apply("2-1")) / 2;
                    double bp12 = (pair.apply("1-2") + pair.apply("1-0")) / 2;
                    double bp02 = (pair.apply("0-2") + pair.apply("2-0")) / 2;
                    csv.row(c, a, r, bp01, bp12, bp02, run.blocking().getGeneralBlockingProbability());
                }
            }
        }
    }

    // =====================================================================================
    // E5 - RMSA algorithms on NSFNET without physical impairments (L8-b, L12-b)
    // =====================================================================================

    static void algorithms(File out) throws Exception {
        record Variant(String group, String label, String routing, String spectrum, String modulation) {}
        List<Variant> variants = List.of(
            new Variant("spectrum", "FF", "djk", "firstfit", "distance-adaptive"),
            new Variant("spectrum", "LF", "djk", "lastfit", "distance-adaptive"),
            new Variant("spectrum", "EF", "djk", "exactfit", "distance-adaptive"),
            new Variant("spectrum", "RF", "djk", "randomfit", "distance-adaptive"),
            new Variant("routing", "SP (Dijkstra)", "djk", "firstfit", "distance-adaptive"),
            new Variant("routing", "KSP (k=3)", "ksp", "firstfit", "distance-adaptive"),
            new Variant("modulation", "Distance-adaptive", "djk", "firstfit", "distance-adaptive"),
            new Variant("modulation", "Fixed (4QAM)", "djk", "firstfit", "fixed"));
        try (Csv csv = new Csv(out, "e5_algorithms", "group", "variant", "load", "rep", "bp", "utilization")) {
            for (Variant v : variants) {
                for (double load : new double[] {150, 200, 250, 300, 350, 400}) {
                    // lengths x0.5: diameter 3900 km, below the 5000 km reach of 4QAM, so every pair is reachable
                    Scenario s = nsfnet(0.5);
                    s.modulations = MODULATIONS;
                    s.phy("activeQoT", false).phy("activeQoTForOther", false).bitRates(100, 1, 200, 1, 400, 1)
                            .sim("totalSlots", 320).sim("routing", v.routing()).sim("spectrumAssignment", v.spectrum())
                            .sim("modulationSelection", v.modulation())
                            .sim("requests", 110_000).sim("warmUpRequests", 10_000).load(load);
                    List<Run> runs = replicate(s.setup(), 5);
                    for (int r = 0; r < runs.size(); r++) {
                        csv.row(v.group(), '"' + v.label() + '"', load, r, runs.get(r).blocking().getGeneralBlockingProbability(),
                                runs.get(r).utilization());
                    }
                }
            }
        }
    }

    // =====================================================================================
    // E6 - Energy consumption (L11-a/b)
    // =====================================================================================

    static void energy(File out) throws Exception {
        // Deterministic check of the per-circuit power for every modulation format and a few sizes
        try (Csv csv = new Csv(out, "e6_circuit_power", "modulation", "M", "slots", "power_w")) {
            Node a = new Node("0", 1, 1, 0);
            Node b = new Node("1", 1, 1, 0);
            for (Map<String, Object> m : MODULATIONS) {
                ModulationFormat mod = new ModulationFormat((String) m.get("name"), 5000, ((Number) m.get("M")).doubleValue(), 0, 0, 32, 0.1);
                for (int slots : new int[] {1, 4, 16}) {
                    Circuit circuit = new Circuit("c", a, b, List.of(), List.of(), 0, slots - 1, mod, 100.0);
                    csv.row(mod.name(), mod.m(), slots, EnergyConsumptionModel.calculateCircuitPower(circuit, SLOT));
                }
            }
        }

        // Time-averaged network power against Little's law, with and without warm-up
        try (Csv csv = new Csv(out, "e6_energy", "slots", "load_per_direction", "warmup", "rep", "avg_power_w",
                "static_power_w", "bp", "circuit_power_w", "sim_time")) {
            int c = 20;
            for (int warmUp : new int[] {0, 20_000, 60_000}) {
                for (double a : new double[] {5, 10, 15, 20, 25}) {
                    // 40 transceivers per node: never blocking (at most c = 20 circuits per direction). The static
                    // power does not depend on them: 2 OXCs (n = 2, add/drop degree a = 2) + 2 x 3 amplifiers
                    Scenario s = new Scenario().node("0", 40, 40, 0, 2).node("1", 40, 40, 0, 2).link("0", "1", 100);
                    ScenarioSetup setup = s.idealSlots().metric("ConsumedEnergy")
                            .sim("totalSlots", c).sim("requests", 120_000).sim("warmUpRequests", warmUp).load(2 * a).setup();
                    NetworkTopology topology = TopologyMapper.map(setup.networkTopology(), setup.physicalLayer(), c);
                    double staticPower = EnergyConsumptionModel.calculateStaticPower(topology);
                    Circuit one = new Circuit("c", topology.nodes().get(0), topology.nodes().get(1), List.of(), List.of(), 0, 0,
                            topology.modulations().get(0), 25.0);
                    double circuitPower = EnergyConsumptionModel.calculateCircuitPower(one, SLOT);
                    List<Run> runs = replicate(setup, 10);
                    for (int r = 0; r < runs.size(); r++) {
                        Run run = runs.get(r);
                        csv.row(c, a, warmUp, r, run.value("ConsumedEnergy", "Average Total Power (W)"), staticPower,
                                run.blocking().getGeneralBlockingProbability(), circuitPower, run.engine().getCurrentTime());
                    }
                }
            }
        }
    }

    // =====================================================================================
    // E7 - Physical layer models (L9)
    // =====================================================================================

    static PhysicalLayerConfig physical(Map<String, Object> overrides) throws IOException {
        Map<String, Object> p = defaultPhysical();
        p.putAll(overrides);
        return MAPPER.convertValue(p, PhysicalLayerConfig.class);
    }

    /** One directed link of the given length inside a two-node control plane with {@code cores} cores. */
    record SingleLink(ControlPlane cp, Link link, Node a, Node b) {
        static SingleLink of(double km, PhysicalLayerConfig cfg, List<Core> cores) {
            Node a = new Node("A", 1000, 1000, 0);
            Node b = new Node("B", 1000, 1000, 0);
            Link link = new Link("A", "B", km, cores, List.of());
            ModulationFormat mod = new ModulationFormat("4QAM", 5000, 4, 5.92, -16, 32, 0.1);
            NetworkTopology topology = new NetworkTopology(List.of(a, b), List.of(link), List.of(mod));
            return new SingleLink(new ControlPlane(topology, null, cfg.bvtSpectralWidth(), cfg.guardBand(), cfg), link, a, b);
        }

        static SingleLink of(double km, PhysicalLayerConfig cfg) {
            return of(km, cfg, List.of(new Core(0, List.of(), 320)));
        }

        double snr(int start, int end) {
            return PhysicalLayerModel.predictSNR(cp, new Path(List.of(link)), 0, start, end, null, 0);
        }

        Circuit circuit(String id, int core, int start, int end) {
            return new Circuit(id, a, b, List.of(link), List.of(core), start, end, cp.getTopology().modulations().get(0), 100.0);
        }
    }

    static void physicalLayer(File out) throws Exception {
        // (a) ASE of the amplifier chain versus link length (ASE only, 4 slots = 50 GHz, 0 dBm)
        PhysicalLayerConfig aseOnly = physical(Map.of("activeNLI", false, "activeXT", false, "guardBand", 0));
        try (Csv csv = new Csv(out, "e7a_ase", "length_km", "line_amplifiers", "ase_w_per_hz", "snr_db")) {
            for (double km = 20; km <= 4000; km += 20) {
                SingleLink l = SingleLink.of(km, aseOnly);
                csv.row(km, PhysicalLayerModel.numberOfLineAmplifiers(km, 80), l.link().getStaticAseNoise(), 10 * Math.log10(l.snr(0, 3)));
            }
        }

        // (b) SNR versus launch power: ASE + NLI of an isolated 50 GHz channel, 1..40 spans
        try (Csv csv = new Csv(out, "e7b_snr_vs_power", "spans", "power_dbm", "snr_db", "ase_w_per_hz", "sci_w_per_hz")) {
            for (int spans : new int[] {1, 5, 10, 20, 40}) {
                for (double p = -10; p <= 10.001; p += 0.1) {
                    PhysicalLayerConfig cfg = physical(Map.of("activeXT", false, "guardBand", 0, "power", Math.round(p * 10) / 10.0));
                    SingleLink l = SingleLink.of(spans * 80.0, cfg);
                    double sci = PhysicalLayerModel.selfChannelInterference(l.link(), cfg, 4 * SLOT);
                    csv.row(spans, Math.round(p * 10) / 10.0, 10 * Math.log10(l.snr(0, 3)), l.link().getStaticAseNoise(), sci);
                }
            }
        }

        // (c) Transparent reach of each format for 100 Gbps (25 % FEC, dual polarization, guard band 1)
        PhysicalLayerConfig reachCfg = physical(Map.of("activeXT", false));
        try (Csv csv = new Csv(out, "e7c_reach", "modulation", "slots", "snr_threshold_db", "max_range_km", "reach_km", "snr_at_reach_db")) {
            for (Map<String, Object> m : MODULATIONS) {
                ModulationFormat mod = new ModulationFormat((String) m.get("name"), 0, ((Number) m.get("M")).doubleValue(),
                        ((Number) m.get("SNR")).doubleValue(), 0, 32, 0.1);
                int slots = com.snets2.rmsca.modulation.SlotCalculator.requiredSlots(100.0, mod, SLOT, 1, 2.0, 0.25);
                double reach = 0, snrAtReach = Double.NaN;
                for (double km = 10; km <= 20000; km += 10) {
                    double snr = SingleLink.of(km, reachCfg).snr(0, slots - 1);
                    if (snr < mod.getSnrThresholdLinear()) break;
                    reach = km;
                    snrAtReach = 10 * Math.log10(snr);
                }
                csv.row(mod.name(), slots, mod.snrThreshold(), m.get("maxRange"), reach, snrAtReach);
            }
        }

        // (d) Inter-core crosstalk: XT ratio vs length, number of overlapping neighbours and overlap fraction
        PhysicalLayerConfig xtCfg = physical(Map.of("activeNLI", false, "guardBand", 0));
        try (Csv csv = new Csv(out, "e7d_xt", "case", "length_km", "neighbours", "overlap_fraction", "xt_ratio")) {
            for (double km : new double[] {10, 50, 100, 200, 500, 1000, 2000}) {
                List<Core> cores = List.of(new Core(0, List.of(1), 320), new Core(1, List.of(0), 320));
                SingleLink l = SingleLink.of(km, xtCfg, cores);
                l.cp().establishCircuit(l.circuit("n", 1, 0, 3));
                csv.row("length", km, 1, 1.0, PhysicalLayerModel.predictXtRatio(l.cp(), new Path(List.of(l.link())), 0, 0, 3));
            }
            for (int n = 0; n <= 6; n++) {
                List<Core> cores = new ArrayList<>();
                cores.add(new Core(0, List.of(1, 2, 3, 4, 5, 6), 320));
                for (int k = 1; k <= 6; k++) cores.add(new Core(k, List.of(0), 320));
                SingleLink l = SingleLink.of(100, xtCfg, cores);
                for (int k = 1; k <= n; k++) l.cp().establishCircuit(l.circuit("n" + k, k, 0, 3));
                csv.row("neighbours", 100, n, 1.0, PhysicalLayerModel.predictXtRatio(l.cp(), new Path(List.of(l.link())), 0, 0, 3));
            }
            for (int shift = 0; shift <= 4; shift++) {
                List<Core> cores = List.of(new Core(0, List.of(1), 320), new Core(1, List.of(0), 320));
                SingleLink l = SingleLink.of(100, xtCfg, cores);
                l.cp().establishCircuit(l.circuit("n", 1, shift, shift + 3));
                csv.row("overlap", 100, 1, (4 - shift) / 4.0, PhysicalLayerModel.predictXtRatio(l.cp(), new Path(List.of(l.link())), 0, 0, 3));
            }
        }

        // (e) Amplifier load: SNR of a probe channel vs number of active channels, fixed vs saturated gain
        try (Csv csv = new Csv(out, "e7e_saturation", "gain", "length_km", "active_channels", "total_power_dbm", "ase_w_per_hz", "snr_ase_db")) {
            for (int gain : new int[] {0, 1}) {
                PhysicalLayerConfig cfg = physical(Map.of("activeNLI", false, "activeXT", false, "guardBand", 0, "typeOfAmplifierGain", gain));
                for (int n = 0; n <= 76; n += 2) {
                    SingleLink l = SingleLink.of(800, cfg);
                    for (int k = 0; k < n; k++) l.cp().establishCircuit(l.circuit("c" + k, 0, 4 + 4 * k, 7 + 4 * k));
                    Core core = l.link().getCore(0);
                    double snr = l.snr(0, 3); // probe on the free slots 0..3, counted in the load
                    double ase = PhysicalLayerModel.calculateLinkAse(l.link(), cfg, core.getTotalLaunchPower() + 1E-3);
                    csv.row(gain == 0 ? "fixed" : "saturated", 800, n + 1,
                            PhysicalLayerModel.wattsToDbm(core.getTotalLaunchPower() + 1E-3), ase, 10 * Math.log10(snr));
                }
            }
        }

        // (f) Launch power and PSD versus circuit width, variable vs fixed PSD
        try (Csv csv = new Csv(out, "e7f_psd", "mode", "signal_slots", "launch_power_dbm", "psd_dbm_per_ghz")) {
            for (boolean fixed : new boolean[] {false, true}) {
                PhysicalLayerConfig cfg = physical(Map.of("fixedPowerSpectralDensity", fixed));
                for (int n = 1; n <= 16; n++) {
                    double bw = n * SLOT;
                    double p = PhysicalLayerModel.circuitLaunchPower(cfg, bw);
                    csv.row(fixed ? "fixed PSD" : "variable PSD", n, PhysicalLayerModel.wattsToDbm(p),
                            PhysicalLayerModel.wattsToDbm(p / (bw / 1E9)));
                }
            }
        }
    }

    // =====================================================================================
    // E8 - Network with physical impairments (L9-k, L10)
    // =====================================================================================

    static void qotNetwork(File out) throws Exception {
        record Variant(String group, String label, Map<String, Object> physical, String core, String modulation,
                       String spectrum) {
            Variant(String group, String label, Map<String, Object> physical, String core, String modulation) {
                this(group, label, physical, core, modulation, "firstfit");
            }
        }
        Map<String, Object> off = Map.of("activeQoT", false, "activeQoTForOther", false);
        Map<String, Object> ase = Map.of("activeNLI", false, "activeXT", false, "activeXTForOther", false);
        Map<String, Object> aseNli = Map.of("activeXT", false, "activeXTForOther", false);
        Map<String, Object> all = Map.of();
        List<Variant> variants = List.of(
            new Variant("impairments", "No QoT", off, "firstfitcore", "distance-adaptive"),
            new Variant("impairments", "ASE", ase, "firstfitcore", "distance-adaptive"),
            new Variant("impairments", "ASE+NLI", aseNli, "firstfitcore", "distance-adaptive"),
            new Variant("impairments", "ASE+NLI+XT", all, "firstfitcore", "distance-adaptive"),
            new Variant("core", "First-fit core", all, "firstfitcore", "distance-adaptive"),
            new Variant("core", "Random-fit core", all, "randomfitcore", "distance-adaptive"),
            new Variant("core", "Min-crosstalk core", all, "mincrosstalkcore", "distance-adaptive"),
            // Issue #18: peripheral-first order, slot-aware XT order and core-staggered spectrum
            new Variant("core", "Peripheral-first core", all, "peripheralfirstcore", "distance-adaptive"),
            new Variant("core", "XT-aware core", all, "xtawarecore", "distance-adaptive"),
            new Variant("core", "Peripheral-first core + staggered FF", all, "peripheralfirstcore", "distance-adaptive",
                    "corestaggeredfit"),
            new Variant("core", "XT-aware core + staggered FF", all, "xtawarecore", "distance-adaptive", "corestaggeredfit"),
            // Issue #23: modulation chosen by maxRange vs by the physical model (ASE + NLI + XT, QoTO/XTO on)
            new Variant("modulation", "distance-adaptive", all, "firstfitcore", "distance-adaptive"),
            new Variant("modulation", "qot-adaptive", all, "firstfitcore", "qot-adaptive"));
        List<String> modNames = MODULATIONS.stream().map(m -> (String) m.get("name")).toList();
        List<String> header = new ArrayList<>(List.of("group", "variant", "load", "rep", "bp", "bp_fragmentation",
                "bp_qot_new", "bp_qot_others", "bp_xt", "bp_xt_others", "mean_snr_db", "mean_slots"));
        modNames.forEach(m -> header.add("share_" + m));
        try (Csv csv = new Csv(out, "e8_qot_network", header.toArray(String[]::new))) {
            for (Variant v : variants) {
                for (double load : new double[] {400, 600, 800, 1000, 1200}) {
                    Scenario s = nsfnet(0.25);
                    s.modulations = MODULATIONS;
                    s.cores = hexagonal7();
                    s.physical.putAll(v.physical());
                    s.bitRates(100, 1, 200, 1, 400, 1).metric("CrosstalkStatistics")
                            .metric("SpectrumSizeStatistics").metric("ModulationUtilization")
                            .sim("totalSlots", 128).sim("modulationSelection", v.modulation())
                            .sim("coreAndSpectrumAssignment", v.core()).sim("spectrumAssignment", v.spectrum())
                            .sim("requests", 22_000).sim("warmUpRequests", 2_000).load(load);
                    List<Run> runs = replicate(s.setup(), 5);
                    for (int r = 0; r < runs.size(); r++) {
                        BitRateBlockingMetrics b = runs.get(r).blocking();
                        double req = b.getGeneralRequestedBitRate();
                        Run run = runs.get(r);
                        List<Object> row = new ArrayList<>(List.of(v.group(), '"' + v.label() + '"', load, r,
                                b.getGeneralBlockingProbability(),
                                b.getBitRateBlockingByFragmentation() / req, b.getBitRateBlockingByQoTN() / req,
                                b.getBitRateBlockingByQoTO() / req, b.getBitRateBlockingByXt() / req, b.getBitRateBlockingByXtOther() / req,
                                run.value("PhysicalLayerStatistics", "Average OSNR (dB)",
                                        Map.of("src", "all", "dest", "all", "overlaps", "all")),
                                meanSlotsPerCircuit(run)));
                        Map<String, Long> perMod = run.engine().getMetricsManager().getModulationUtilization().getCountPerModulation();
                        long total = run.engine().getMetricsManager().getModulationUtilization().getTotalCircuits();
                        for (String m : modNames) row.add(total == 0 ? 0.0 : perMod.getOrDefault(m, 0L) / (double) total);
                        csv.row(row.toArray());
                    }
                }
            }
        }
    }

    /** Mean number of slots (guard band included) of the circuits established in the measured period. */
    static double meanSlotsPerCircuit(Run run) {
        double mean = 0;
        for (SimulationResult.MetricRow row : run.result().getData().getOrDefault("SpectrumSizeStatistics", Map.of()).values()) {
            if (!row.getSubMetric().equals("Percentage per Slot Size") || !"all".equals(row.getDimensions().get("link"))) continue;
            mean += Integer.parseInt(row.getDimensions().get("slots")) * row.getRepValues().getOrDefault(0, 0.0);
        }
        return mean;
    }
}
