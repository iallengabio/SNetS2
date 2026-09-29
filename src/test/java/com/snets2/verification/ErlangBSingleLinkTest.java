package com.snets2.verification;

import com.snets2.config.ConfigLoader;
import com.snets2.config.ExperimentSetup;
import com.snets2.config.ScenarioSetup;
import com.snets2.engine.ArrivalEvent;
import com.snets2.engine.ResourceUtilizationObservationEvent;
import com.snets2.engine.SimulationEngine;
import com.snets2.model.ControlPlane;
import com.snets2.model.NetworkTopology;
import com.snets2.model.Node;
import com.snets2.config.TopologyMapper;
import com.snets2.rmsca.AlgorithmFactory;
import com.snets2.rmsca.IRMSCA;
import com.snets2.rmsca.StandardIntegratedRMSCA;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verification level L2 (see docs/review/03_plano_de_verificacao.md): a single directed link with
 * {@code c} slots, one core, no QoT, every request needing exactly one slot, is an M/M/c/c loss system.
 * The blocking probability must therefore match the Erlang-B formula, and the time-averaged
 * spectrum utilization must equal {@code A (1 - B) / c}.
 *
 * <p>Topology: two nodes joined by two opposite directed links; the traffic generator picks
 * (src, dst) uniformly, so each direction receives {@code load / 2} Erlangs.</p>
 */
class ErlangBSingleLinkTest {

    private static final int REPLICATIONS = 6;
    private static final int REQUESTS = 80_000;
    private static final int WARM_UP = 5_000;

    /** Erlang-B via the numerically stable recursion. */
    static double erlangB(double offeredLoad, int servers) {
        double b = 1.0;
        for (int k = 1; k <= servers; k++) {
            b = offeredLoad * b / (k + offeredLoad * b);
        }
        return b;
    }

    private static String setupJson(double totalLoad, int slots) {
        // bitRate 25 Gbps, 4QAM (2 bit/symbol), 12.5 GHz slot, guard band 0  =>  exactly 1 slot per request.
        return """
        {
          "networkTopology": {
            "nodes": [
              {"id": "0", "tx": 1000000, "rx": 1000000, "regenerators": 0},
              {"id": "1", "tx": 1000000, "rx": 1000000, "regenerators": 0}
            ],
            "links": [
              {"source": "0", "destination": "1", "length": 100.0},
              {"source": "1", "destination": "0", "length": 100.0}
            ],
            "cores": [ {"id": 0, "adjacentCores": []} ],
            "modulations": [ {"name": "4QAM", "maxRange": 5000.0, "M": 4.0, "SNR": 8.95, "XT": -19.03} ]
          },
          "physicalLayer": {
            "activeQoT": false, "activeQoTForOther": false,
            "guardBand": 0, "bvtSpectralWidth": 12.5E9, "spanLength": 80.0,
            "fiberLoss": 0.2, "constantOfPlanck": 6.626E-34, "amplificationFrequency": 1.9385E14,
            "noiseFigureOfOpticalAmplifier": 5.0
          },
          "simulation": {
            "requests": %d, "warmUpRequests": %d, "totalSlots": %d,
            "routing": "djk", "spectrumAssignment": "firstfit", "coreAndSpectrumAssignment": "firstfitcore",
            "integratedRMSCA": "standard", "modulationSelection": "fixed",
            "activeMetrics": {
              "BlockingProbability": true, "SpectrumUtilization": true,
              "ExternalFragmentation": false, "RelativeFragmentation": false,
              "TransmittersReceiversRegeneratorsUtilization": false, "ModulationUtilization": false,
              "SpectrumSizeStatistics": false, "ConsumedEnergy": false,
              "SimulationMetadata": false, "CrosstalkStatistics": false
            }
          },
          "traffic": { "loadDistributionPerPair": "uniform", "load": %s,
                       "bitRates": [ {"value": 25.0, "weight": 1.0} ] },
          "experimentalPlanning": { "replications": 1 }
        }
        """.formatted(REQUESTS, WARM_UP, slots, Double.toString(totalLoad));
    }

    private static SimulationEngine runReplication(ScenarioSetup setup, long seed) {
        NetworkTopology topology = TopologyMapper.map(setup.networkTopology(), setup.physicalLayer(),
                setup.simulation().totalSlots());
        IRMSCA rmsca = AlgorithmFactory.createIntegrated(setup.simulation().integratedRMSCA());
        StandardIntegratedRMSCA standard = (StandardIntegratedRMSCA) rmsca;
        standard.setRouting(AlgorithmFactory.createRouting(setup.simulation().routing()));
        standard.setCoreAssignment(AlgorithmFactory.createCore(setup.simulation().coreAndSpectrumAssignment()));
        standard.setSpectrumAssignment(AlgorithmFactory.createSpectrum(setup.simulation().spectrumAssignment()));

        ControlPlane cp = new ControlPlane(topology, rmsca, setup.physicalLayer().bvtSpectralWidth(),
                setup.physicalLayer().guardBand(), setup.physicalLayer());
        SimulationEngine engine = new SimulationEngine(topology, cp, setup.simulation().requests(),
                setup.simulation().warmUpRequests(), setup.simulation().activeMetrics(),
                setup.traffic().load(), setup.traffic().bitRates(), seed);

        List<Node> nodes = topology.nodes();
        Node src = nodes.get(engine.getRandom().nextInt(nodes.size()));
        Node dst;
        do {
            dst = nodes.get(engine.getRandom().nextInt(nodes.size()));
        } while (src == dst);
        engine.schedule(new ArrivalEvent(0.0, src, dst, engine.nextBitRate()));
        engine.schedule(new ResourceUtilizationObservationEvent(0.0));
        engine.run();
        return engine;
    }

    private record Stats(double mean, double standardError) {}

    private static Stats blockingStats(double totalLoad, int slots) throws Exception {
        ExperimentSetup setup = ConfigLoader.load(setupJson(totalLoad, slots));
        double sum = 0, sumSq = 0;
        for (int r = 0; r < REPLICATIONS; r++) {
            double bp = runReplication(setup.getBaseScenario(), r)
                    .getMetricsManager().getBitRateBlocking().getGeneralBlockingProbability();
            sum += bp;
            sumSq += bp * bp;
        }
        double mean = sum / REPLICATIONS;
        double variance = Math.max(0, (sumSq - REPLICATIONS * mean * mean) / (REPLICATIONS - 1));
        return new Stats(mean, Math.sqrt(variance / REPLICATIONS));
    }

    @Test
    @DisplayName("Blocking probability of one directed link equals Erlang-B(A = load/2, c = slots)")
    void blockingMatchesErlangB() throws Exception {
        // {total load, slots}: per-direction offered load is load/2.
        double[][] cases = { {1.0, 1}, {2.0, 2}, {12.0, 5}, {30.0, 20} };
        for (double[] cs : cases) {
            double load = cs[0];
            int slots = (int) cs[1];
            double expected = erlangB(load / 2.0, slots);
            Stats s = blockingStats(load, slots);
            // 4 standard errors plus a small absolute floor for the finite-run (warm-up) bias.
            double tolerance = 4 * s.standardError() + 0.002;
            assertEquals(expected, s.mean(), tolerance,
                    "BP mismatch for A=" + (load / 2) + " Erlang, c=" + slots
                            + " (expected " + expected + ", got " + s.mean() + " ± " + s.standardError() + ")");
        }
    }

    @Test
    @DisplayName("Blocking probability is monotonically increasing with the offered load")
    void blockingIsMonotonicInLoad() throws Exception {
        double previous = -1;
        for (double load : new double[] {16.0, 24.0, 30.0, 40.0}) {
            double bp = blockingStats(load, 20).mean();
            assertTrue(bp > previous, "BP should increase with load; load=" + load + " bp=" + bp + " prev=" + previous);
            previous = bp;
        }
    }

    @Test
    @Disabled("Known defect CR-02 (docs/review/02_code_review.md): time-weighted metrics are sampled AFTER the state "
            + "change, so utilization is biased (c=1: ~0.43 measured vs 0.333 expected). Re-enable after the fix.")
    @DisplayName("Time-averaged spectrum utilization equals A(1-B)/c")
    void utilizationMatchesTheory() throws Exception {
        int slots = 1;
        double load = 1.0;
        double a = load / 2.0;
        double expected = a * (1 - erlangB(a, slots)) / slots;
        ExperimentSetup setup = ConfigLoader.load(setupJson(load, slots));
        double sum = 0;
        for (int r = 0; r < REPLICATIONS; r++) {
            sum += runReplication(setup.getBaseScenario(), r)
                    .getMetricsManager().getResourceUtilization().getAverageGeneralUtilization();
        }
        assertEquals(expected, sum / REPLICATIONS, 0.005);
    }
}
