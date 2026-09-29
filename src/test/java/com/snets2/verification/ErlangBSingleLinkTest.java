package com.snets2.verification;

import com.snets2.config.ConfigLoader;
import com.snets2.config.ExperimentSetup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
        return VerificationSupport.TwoNode.oneSlotPerRequest(REQUESTS, WARM_UP, slots, totalLoad).json();
    }

    private record Stats(double mean, double standardError) {}

    private static Stats blockingStats(double totalLoad, int slots) throws Exception {
        ExperimentSetup setup = ConfigLoader.load(setupJson(totalLoad, slots));
        double sum = 0, sumSq = 0;
        for (int r = 0; r < REPLICATIONS; r++) {
            double bp = VerificationSupport.runReplication(setup.getBaseScenario(), r)
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
    @DisplayName("Time-averaged spectrum utilization equals A(1-B)/c (regression test for CR-02)")
    void utilizationMatchesTheory() throws Exception {
        // {total load, slots}; c=1 and c=2 exposed the post-mutation sampling bias (0.426 vs 0.333).
        double[][] cases = { {1.0, 1}, {2.0, 2}, {30.0, 20} };
        for (double[] cs : cases) {
            double load = cs[0];
            int slots = (int) cs[1];
            double a = load / 2.0;
            double expected = a * (1 - erlangB(a, slots)) / slots;
            ExperimentSetup setup = ConfigLoader.load(setupJson(load, slots));
            double sum = 0;
            for (int r = 0; r < REPLICATIONS; r++) {
                sum += VerificationSupport.runReplication(setup.getBaseScenario(), r)
                        .getMetricsManager().getResourceUtilization().getAverageGeneralUtilization();
            }
            assertEquals(expected, sum / REPLICATIONS, 0.005,
                    "Utilization mismatch for A=" + a + " Erlang, c=" + slots);
        }
    }
}
