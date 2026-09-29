package com.snets2.verification;

import com.snets2.config.ConfigLoader;
import com.snets2.config.ScenarioSetup;
import com.snets2.engine.SimulationEngine;
import com.snets2.metrics.BitRateBlockingMetrics;
import com.snets2.metrics.ModulationUtilizationMetrics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the engine accounting, the modulation policy and reproducibility
 * (verification levels L1-c, L1-f and L8-b of docs/review/03_plano_de_verificacao.md;
 * defects CR-03, CR-04 and CR-08 of docs/review/02_code_review.md).
 */
class EngineAccountingAndConfigTest {

    private static final double BIT_RATE = 25.0;

    private static ScenarioSetup scenario(VerificationSupport.TwoNode params) throws Exception {
        return ConfigLoader.load(params.json()).getBaseScenario();
    }

    private static long count(double bitRateSum) {
        return Math.round(bitRateSum / BIT_RATE);
    }

    @Test
    @DisplayName("L1-c: every measured arrival has exactly one recorded outcome (all requests blocked)")
    void everyMeasuredArrivalIsBlockedWhenNothingFits() throws Exception {
        for (int warmUp : new int[] {0, 100}) {
            // 1 slot per link but 1 guard slot => every request needs 2 slots and is blocked.
            var params = VerificationSupport.TwoNode.oneSlotPerRequest(1000, warmUp, 1, 1.0).withBitRate(BIT_RATE, 1);
            SimulationEngine engine = VerificationSupport.runReplication(scenario(params), 1);
            BitRateBlockingMetrics bp = engine.getMetricsManager().getBitRateBlocking();

            long arrivals = count(bp.getGeneralRequestedBitRate());
            long blocked = count(bp.getGeneralBlockingProbability() * bp.getGeneralRequestedBitRate());
            assertEquals(1000 - warmUp, arrivals, "measured arrivals (warm-up=" + warmUp + ")");
            assertEquals(arrivals, blocked, "blocked must equal arrivals (warm-up=" + warmUp + ")");
        }
    }

    @Test
    @DisplayName("L1-c: measured arrivals = accepted + blocked (mixed outcomes, with warm-up)")
    void arrivalsEqualAcceptedPlusBlocked() throws Exception {
        for (int warmUp : new int[] {0, 137}) {
            var params = VerificationSupport.TwoNode.oneSlotPerRequest(5000, warmUp, 3, 6.0);
            SimulationEngine engine = VerificationSupport.runReplication(scenario(params), 7);
            BitRateBlockingMetrics bp = engine.getMetricsManager().getBitRateBlocking();

            long arrivals = count(bp.getGeneralRequestedBitRate());
            long blocked = count(bp.getGeneralBlockingProbability() * bp.getGeneralRequestedBitRate());
            long accepted = engine.getMetricsManager().getModulationUtilization().getTotalCircuits();
            assertEquals(5000 - warmUp, arrivals);
            assertTrue(blocked > 0 && accepted > 0, "scenario must produce both outcomes");
            assertEquals(arrivals, accepted + blocked, "arrivals = accepted + blocked (warm-up=" + warmUp + ")");
        }
    }

    @Test
    @DisplayName("L8-b: 'fixed' uses a single format; 'distance-adaptive' prefers the most efficient reachable one")
    void modulationSelectionIsHonoured() throws Exception {
        String mods = """
            {"name": "4QAM", "maxRange": 5000.0, "M": 4.0, "SNR": 8.95, "XT": -19.03},
            {"name": "16QAM", "maxRange": 1250.0, "M": 16.0, "SNR": 15.49, "XT": -25.57}""";
        var base = VerificationSupport.TwoNode.oneSlotPerRequest(3000, 0, 40, 4.0).withBitRate(100.0, 0);

        ModulationUtilizationMetrics fixed = VerificationSupport.runReplication(
                scenario(base.withModulations(mods, "fixed")), 3).getMetricsManager().getModulationUtilization();
        assertEquals(Map.of("4QAM", fixed.getTotalCircuits()), fixed.getCountPerModulation(),
                "fixed policy must only use the first listed format (no BPSK available)");

        ModulationUtilizationMetrics adaptive = VerificationSupport.runReplication(
                scenario(base.withModulations(mods, "distance-adaptive")), 3).getMetricsManager().getModulationUtilization();
        assertEquals(Map.of("16QAM", adaptive.getTotalCircuits()), adaptive.getCountPerModulation(),
                "100 km path with abundant spectrum: distance-adaptive must always pick 16QAM");
    }

    @Test
    @DisplayName("L1-f: same seed => identical results, also with Random Fit")
    void randomFitIsReproducible() throws Exception {
        var params = VerificationSupport.TwoNode.oneSlotPerRequest(4000, 200, 8, 10.0)
                .withBitRate(50.0, 0).withSpectrumAssignment("randomfit");
        double[] bp = new double[2];
        double[] util = new double[2];
        for (int i = 0; i < 2; i++) {
            SimulationEngine engine = VerificationSupport.runReplication(scenario(params), 11);
            bp[i] = engine.getMetricsManager().getBitRateBlocking().getGeneralBlockingProbability();
            util[i] = engine.getMetricsManager().getResourceUtilization().getAverageGeneralUtilization();
        }
        assertEquals(bp[0], bp[1], 0.0);
        assertEquals(util[0], util[1], 0.0);
    }
}
