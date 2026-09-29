package com.snets2.verification;

import com.snets2.config.ConfigLoader;
import com.snets2.config.PhysicalLayerConfig;
import com.snets2.config.ScenarioSetup;
import com.snets2.config.TopologyMapper;
import com.snets2.engine.SimulationEngine;
import com.snets2.metrics.ConsumedEnergyMetrics;
import com.snets2.metrics.EnergyConsumptionModel;
import com.snets2.metrics.PhysicalLayerModel;
import com.snets2.model.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Energy model (verification level L11 of docs/review/03_plano_de_verificacao.md; regression tests for
 * issues #13, #14 and #15, CR-11).
 */
class EnergyModelTest {

    private static final double SLOT = 12.5E9;

    // ------------------------------------------------------------------------------------------
    // #13 - mean power over the measured window [T_w, T]
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("#13: energy and peak are integrated only over [T_w, T] and the mean divides by T - T_w")
    void measuredWindowIntegration() {
        ConsumedEnergyMetrics m = new ConsumedEnergyMetrics(100.0);
        // Warm-up: 1000 W of dynamic power during (0, 10], discarded
        m.addCircuitPower(1000.0);
        m.update(10.0);
        m.removeCircuitPower(1000.0);
        m.addCircuitPower(50.0);
        m.update(12.0);
        // Warm-up ends at t = 15 with 50 W of dynamic power, which holds until t = 20
        m.startMeasurement(15.0);
        m.update(20.0);
        m.addCircuitPower(200.0);
        m.update(25.0);                      // 350 W during (20, 25]
        m.removeCircuitPower(250.0);
        m.update(35.0);                      // 100 W during (25, 35]

        double energy = 150.0 * 5 + 350.0 * 5 + 100.0 * 10;
        assertEquals(energy, m.getTotalEnergyJoule(), 1E-9);
        assertEquals(energy / 20.0, m.getAveragePower(35.0), 1E-9);
        assertEquals(15.0, m.getMeasurementStartTime());
    }

    @Test
    @DisplayName("#13: without warm-up the window starts at t = 0")
    void noWarmUpWindow() {
        ConsumedEnergyMetrics m = new ConsumedEnergyMetrics(10.0);
        m.update(4.0);
        m.addCircuitPower(30.0);
        m.update(8.0);
        assertEquals((10.0 * 4 + 40.0 * 4) / 8.0, m.getAveragePower(8.0), 1E-12);
    }

    /**
     * Two nodes, c = 20 slots, A = 10 Erlang per direction, one slot per request and never blocking by
     * transceiver: by Little's law the mean number of active circuits is 2 A (1 - B), hence
     * P = P_static + P_circuit 2 A (1 - B). Before #13 the warm-up case was biased by (1 - T_w / T), i.e. -33 %.
     */
    @Test
    @DisplayName("#13: mean network power with warm-up satisfies Little's law")
    void meanPowerWithWarmUpMatchesLittle() throws Exception {
        int slots = 20;
        double a = 10.0;
        int replications = 5;
        for (int warmUp : new int[] {0, 20_000}) {
            String json = VerificationSupport.TwoNode.oneSlotPerRequest(60_000, warmUp, slots, 2 * a).json()
                    .replace("\"ConsumedEnergy\": false", "\"ConsumedEnergy\": true");
            ScenarioSetup setup = ConfigLoader.load(json).getBaseScenario();

            double sum = 0, sumSq = 0, theory = 0;
            for (int r = 0; r < replications; r++) {
                SimulationEngine engine = VerificationSupport.runReplication(setup, r);
                if (warmUp > 0) assertTrue(engine.getWarmUpEndTime() > 0, "warm-up end instant must be recorded");
                else assertEquals(0.0, engine.getWarmUpEndTime());
                double p = engine.getMetricsManager().getConsumedEnergy().getAveragePower(engine.getCurrentTime());
                sum += p;
                sumSq += p * p;

                NetworkTopology topology = engine.getTopology();
                Circuit one = new Circuit("c", topology.nodes().get(0), topology.nodes().get(1), List.of(), List.of(),
                        0, 0, topology.modulations().get(0), 25.0);
                theory = EnergyConsumptionModel.calculateStaticPower(topology)
                        + EnergyConsumptionModel.calculateCircuitPower(one, SLOT) * 2 * a * (1 - ErlangBSingleLinkTest.erlangB(a, slots));
            }
            double mean = sum / replications;
            double se = Math.sqrt(Math.max(0, (sumSq - replications * mean * mean) / (replications - 1)) / replications);
            assertEquals(theory, mean, 4 * se + 0.003 * theory,
                    "warm-up " + warmUp + ": expected " + theory + " W, got " + mean + " ± " + se);
        }
    }

    // ------------------------------------------------------------------------------------------
    // #14 - OXC add/drop term
    // ------------------------------------------------------------------------------------------

    private static PhysicalLayerConfig physical(double spanLength, double fiberLoss, double switchInsertionLoss) {
        return new PhysicalLayerConfig(
            true, true, true, false, false, false,
            0.25, 0.0, spanLength, fiberLoss, 0.0013, 1.6E-5, 1.9385E14,
            6.626E-34, 5.0, 16.0, 100.0, 4.0, 0, 1.9385E14, switchInsertionLoss,
            false, 1.25E10, 1.0E7, 0.01, 0.012, 4.5E-5, 2.0, 0, SLOT);
    }

    private static NetworkTopology twoNodes(Node a, Node b, List<Amplifier> amplifiers) {
        Link ab = new Link(a.getId(), b.getId(), 100, List.of(new Core(0, List.of(), 20)), amplifiers);
        Link ba = new Link(b.getId(), a.getId(), 100, List.of(new Core(0, List.of(), 20)), amplifiers);
        ModulationFormat mod = new ModulationFormat("4QAM", 5000, 4, 5.92, -16, 32, 0.1);
        return new NetworkTopology(List.of(a, b), List.of(ab, ba), List.of(mod));
    }

    @Test
    @DisplayName("#14: static power is 85 n + 100 a + 150 + 80 r per node, independent of the installed transceivers")
    void staticPowerUsesAddDropDegree() {
        double few = EnergyConsumptionModel.calculateStaticPower(
                twoNodes(new Node("0", 1, 1, 2, 3), new Node("1", 1, 1, 0, 1), List.of()));
        double many = EnergyConsumptionModel.calculateStaticPower(
                twoNodes(new Node("0", 100_000, 100_000, 2, 3), new Node("1", 100_000, 100_000, 0, 1), List.of()));
        assertEquals(few, many, 0.0);

        // n = 2 directed links per node; node 0: a = 3, r = 2; node 1: a = 1, r = 0
        double expected = (85 * 2 + 100 * 3 + 150 + 80 * 2) + (85 * 2 + 100 * 1 + 150);
        assertEquals(expected, many, 1E-9);

        // Default add/drop degree
        double byDefault = EnergyConsumptionModel.calculateStaticPower(
                twoNodes(new Node("0", 10, 10, 0), new Node("1", 10, 10, 0), List.of()));
        assertEquals(2 * EnergyConsumptionModel.oxcPower(2, Node.DEFAULT_ADD_DROP_DEGREE), byDefault, 1E-9);
    }

    // ------------------------------------------------------------------------------------------
    // #15 - amplifiers counted by the energy model = amplifiers of the ASE chain
    // ------------------------------------------------------------------------------------------

    private static final String TOPOLOGY_JSON = """
        {
          "networkTopology": {
            "nodes": [ {"id": "0", "tx": 10, "rx": 10, "regenerators": 0, "addDropDegree": 2},
                       {"id": "1", "tx": 10, "rx": 10, "regenerators": 0} ],
            "links": [ {"source": "0", "destination": "1", "length": %s},
                       {"source": "1", "destination": "0", "length": %s} ],
            "cores": [ {"id": 0, "adjacentCores": []} ],
            "modulations": [ {"name": "4QAM", "maxRange": 5000.0, "M": 4.0, "SNR": 8.95, "XT": -19.03} ]
          },
          "physicalLayer": { "activeQoT": true, "activeASE": true, "guardBand": 0, "bvtSpectralWidth": 12.5E9,
                             "spanLength": 80.0, "fiberLoss": 0.2, "switchInsertionLoss": 5.0,
                             "noiseFigureOfOpticalAmplifier": 5.0, "constantOfPlanck": 6.626E-34,
                             "amplificationFrequency": 1.9385E14 },
          "simulation": { "requests": 100, "totalSlots": 32, "routing": "djk", "spectrumAssignment": "firstfit",
                          "coreAndSpectrumAssignment": "firstfitcore", "integratedRMSCA": "standard" },
          "traffic": { "load": 10.0 },
          "experimentalPlanning": { "replications": 1 }
        }
        """;

    @Test
    @DisplayName("#15: each link has booster + N_l line + pre-amplifier, with the gains of the ASE model")
    void amplifierChainMatchesAseModel() throws Exception {
        for (double km : new double[] {1, 50, 79.9, 80, 80.1, 100, 160, 1000, 2345.6}) {
            ScenarioSetup setup = ConfigLoader.load(TOPOLOGY_JSON.formatted(km, km)).getBaseScenario();
            PhysicalLayerConfig cfg = setup.physicalLayer();
            NetworkTopology topology = TopologyMapper.map(setup.networkTopology(), cfg, 32);
            Link link = topology.links().get(0);

            int nLine = PhysicalLayerModel.numberOfLineAmplifiers(km, cfg.spanLength());
            List<Amplifier> amps = link.getAmplifiers();
            assertEquals(nLine + 2, amps.size(), "amplifiers of a " + km + " km link");

            // Independent gains: booster 3 L_sss, line alpha L_span, pre alpha (L - N_l L_span)
            assertEquals(3 * 5.0, amps.get(0).getGain(), 1E-12);
            for (int k = 1; k <= nLine; k++) assertEquals(0.2 * 80.0, amps.get(k).getGain(), 1E-12);
            assertEquals(0.2 * (km - nLine * 80.0), amps.get(nLine + 1).getGain(), 1E-9);

            // Fixed-gain ASE rebuilt from the amplifier objects equals the ASE model
            double ase = 0;
            for (Amplifier amp : amps) {
                ase += PhysicalLayerModel.amplifierAse(cfg, PhysicalLayerModel.dbToLinear(amp.getGain()), 0);
            }
            assertEquals(PhysicalLayerModel.calculateLinkAse(link, cfg, 0), ase, 1E-12 * ase);

            // Energy: 100 W per amplifier of the chain; node 0 has a = 2, node 1 the default a = 1
            double expected = 2 * (nLine + 2) * EnergyConsumptionModel.AMPLIFIER_POWER_W
                    + EnergyConsumptionModel.oxcPower(2, 2) + EnergyConsumptionModel.oxcPower(2, Node.DEFAULT_ADD_DROP_DEGREE);
            assertEquals(expected, EnergyConsumptionModel.calculateStaticPower(topology), 1E-9);
        }
    }
}
