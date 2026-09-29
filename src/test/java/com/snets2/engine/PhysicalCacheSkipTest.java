package com.snets2.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.snets2.ExperimentalPlanner;
import com.snets2.config.ConfigLoader;
import com.snets2.config.ConfigValidator;
import com.snets2.config.PhysicalLayerConfig;
import com.snets2.config.ScenarioSetup;
import com.snets2.model.*;
import com.snets2.output.SimulationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The physical caches (NLI, XT, core load) are only maintained when the QoT check or the
 * CrosstalkStatistics metric can read them (issue #19), and skipping them does not change the results.
 */
class PhysicalCacheSkipTest {

    private static final String PHYSICAL_SHEET = "PhysicalLayerStatistics";

    /** experiment01 (3 nodes, 7 cores) with saturated gain, every metric on and the QoT / CrosstalkStatistics given. */
    private static ScenarioSetup scenario(boolean qot, boolean crosstalkStatistics, int requests) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = (ObjectNode) mapper.readTree(new File("experiments/experiment01/setup.json"));
        ObjectNode phy = (ObjectNode) root.get("physicalLayer");
        phy.put("activeQoT", qot).put("activeQoTForOther", qot).put("typeOfAmplifierGain", 1);
        ObjectNode sim = (ObjectNode) root.get("simulation");
        sim.put("requests", requests).put("warmUpRequests", requests / 10);
        ObjectNode metrics = sim.putObject("activeMetrics");
        for (String m : ConfigValidator.KNOWN_METRICS) metrics.put(m, true);
        metrics.put("CrosstalkStatistics", crosstalkStatistics);
        ((ObjectNode) root.get("traffic")).put("load", 1000.0);
        root.remove("experimentalPlanning");
        return ConfigLoader.load(mapper.writeValueAsString(root)).getBaseScenario();
    }

    private record Run(SimulationEngine engine, SimulationResult result) {}

    private static Run run(ScenarioSetup setup, int seed) {
        SimulationResult result = new SimulationResult(1);
        SimulationEngine engine = ExperimentalPlanner.runReplication(setup, seed, result, Map.of());
        return new Run(engine, result);
    }

    /** Every value of every sheet except the physical statistics, keyed by sheet and row. */
    private static Map<String, Map<Integer, Double>> valuesWithoutPhysicalSheet(SimulationResult result) {
        Map<String, Map<Integer, Double>> values = new TreeMap<>();
        result.getData().forEach((sheet, rows) -> {
            if (sheet.equals(PHYSICAL_SHEET)) return;
            rows.forEach((key, row) -> values.put(sheet + "|" + key, row.getRepValues()));
        });
        return values;
    }

    @Test
    @DisplayName("QoT off: skipping the caches gives exactly the same results as maintaining them")
    void skippedCachesDoNotChangeResults() throws Exception {
        for (int seed = 0; seed < 2; seed++) {
            Run maintained = run(scenario(false, true, 10_000), seed);
            Run skipped = run(scenario(false, false, 10_000), seed);

            ControlPlane cpMaintained = maintained.engine().getControlPlane();
            assertTrue(cpMaintained.isNliCacheActive() && cpMaintained.isXtCacheActive() && cpMaintained.isCoreLoadActive());
            ControlPlane cpSkipped = skipped.engine().getControlPlane();
            assertFalse(cpSkipped.isNliCacheActive() || cpSkipped.isXtCacheActive() || cpSkipped.isCoreLoadActive());

            assertTrue(maintained.result().getData().containsKey(PHYSICAL_SHEET));
            double bp = maintained.engine().getMetricsManager().getBitRateBlocking().getGeneralBlockingProbability();
            assertTrue(bp > 0 && bp < 1, "scenario should block some requests, bp=" + bp);

            assertEquals(valuesWithoutPhysicalSheet(maintained.result()), valuesWithoutPhysicalSheet(skipped.result()));
            assertEquals(maintained.engine().getCurrentTime(), skipped.engine().getCurrentTime());
        }
    }

    @Test
    @DisplayName("QoT on: the caches are maintained even without CrosstalkStatistics")
    void qotKeepsCaches() throws Exception {
        ControlPlane cp = run(scenario(true, false, 200), 0).engine().getControlPlane();
        assertTrue(cp.isNliCacheActive() && cp.isXtCacheActive() && cp.isCoreLoadActive());
    }

    // ---------------------------------------------------------------------------------------------
    // ControlPlane flags
    // ---------------------------------------------------------------------------------------------

    private static PhysicalLayerConfig config(boolean qot, boolean nli, boolean xt, int gain) {
        return new PhysicalLayerConfig(
            qot, qot, true, nli, xt, xt,
            0.07, 0.0, 80.0, 0.2, 1.3E-3, 1.6E-5, 1.93E14,
            6.626E-34, 5.0, 16.0, 100.0, 4.0, gain, 1.93E14, 5.0,
            false, 1.25E10, 1.0E7, 0.01, 0.012, 4.5E-5, 2.0, 1, 12.5E9);
    }

    private static final class TwoCoreLink {
        final Node a = new Node("A", 10, 10, 0);
        final Node b = new Node("B", 10, 10, 0);
        final Core core0 = new Core(0, List.of(1), 100);
        final Core core1 = new Core(1, List.of(0), 100);
        final Link link = new Link("A", "B", 100.0, List.of(core0, core1), List.of());
        final ModulationFormat qpsk = new ModulationFormat("QPSK", 2000.0, 4.0, 12.0, -25.0, 32.0, 0.1);
        final ControlPlane cp;

        TwoCoreLink(PhysicalLayerConfig config) {
            cp = new ControlPlane(new NetworkTopology(List.of(a, b), List.of(link), List.of(qpsk)), null, 12.5E9, 1, config);
        }

        Circuit circuit(String id) {
            return new Circuit(id, a, b, List.of(link), List.of(0), 10, 20, qpsk, 100.0);
        }
    }

    @Test
    void flagsFollowReadersAndSubModels() {
        // Default (no engine): every cache whose model is active is maintained
        ControlPlane cp = new TwoCoreLink(config(false, true, true, PhysicalLayerConfig.AMP_GAIN_SATURATED)).cp;
        assertTrue(cp.isNliCacheActive() && cp.isXtCacheActive() && cp.isCoreLoadActive());

        // No reader: nothing is maintained
        cp.setPhysicalStatisticsRequired(false);
        assertFalse(cp.isNliCacheActive() || cp.isXtCacheActive() || cp.isCoreLoadActive());

        // QoT on: each cache only if its model is active; the load only with saturated gain
        cp = new TwoCoreLink(config(true, false, true, PhysicalLayerConfig.AMP_GAIN_FIXED)).cp;
        cp.setPhysicalStatisticsRequired(false);
        assertFalse(cp.isNliCacheActive());
        assertTrue(cp.isXtCacheActive());
        assertFalse(cp.isCoreLoadActive());

        cp = new TwoCoreLink(config(true, true, false, PhysicalLayerConfig.AMP_GAIN_SATURATED)).cp;
        cp.setPhysicalStatisticsRequired(false);
        assertTrue(cp.isNliCacheActive());
        assertFalse(cp.isXtCacheActive());
        assertTrue(cp.isCoreLoadActive());

        // No physical layer
        cp = new TwoCoreLink(null).cp;
        assertFalse(cp.isNliCacheActive() || cp.isXtCacheActive() || cp.isCoreLoadActive());
    }

    @Test
    void skippedCachesStayEmptyAndFlagsAreFixedWhileCircuitsAreActive() {
        TwoCoreLink net = new TwoCoreLink(config(false, true, true, PhysicalLayerConfig.AMP_GAIN_SATURATED));
        net.cp.setPhysicalStatisticsRequired(false);
        net.cp.establishCircuit(net.circuit("c1"));

        assertEquals(0.0, net.core0.getAverageNliNoise(25, 30));
        assertEquals(0.0, net.core1.getAverageXtNoise(10, 20));
        assertEquals(0.0, net.core0.getTotalLaunchPower());
        assertThrows(IllegalStateException.class, () -> net.cp.setPhysicalStatisticsRequired(true));

        net.cp.teardownCircuit("c1");
        net.cp.setPhysicalStatisticsRequired(true); // allowed again with no active circuit
        assertTrue(net.cp.isNliCacheActive());
    }
}
