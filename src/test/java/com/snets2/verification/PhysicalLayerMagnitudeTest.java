package com.snets2.verification;

import com.snets2.config.PhysicalLayerConfig;
import com.snets2.metrics.PhysicalLayerModel;
import com.snets2.model.*;
import com.snets2.rmsca.routing.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Magnitude checks of the physical layer (verification level L9 of docs/review/03_plano_de_verificacao.md,
 * regression tests for CR-01). Parameters are those of experiments/experiment01/setup.json.
 */
class PhysicalLayerMagnitudeTest {

    private static final double SLOT = 12.5E9;

    private static PhysicalLayerConfig config(double powerDbm, boolean nli) {
        return new PhysicalLayerConfig(
            0, 0, true, true, true, nli, false, false,
            0.25, 0, powerDbm, 80.0, 0.2, 0.0013, 1.6E-5, 1.9385E14,
            6.626E-34, 5.0, 16.0, 100.0, 4.0, 0, 1.9385E14, 5.0,
            false, 1.25E10, 1.0E7, 0.01, 0.012, 4.5E-5, 2.0, 0, SLOT);
    }

    private record Net(ControlPlane cp, Link link, Node a, Node b, ModulationFormat mod) {}

    private static Net net(double lengthKm, PhysicalLayerConfig cfg) {
        Node a = new Node("A", 10, 10, 0);
        Node b = new Node("B", 10, 10, 0);
        Link link = new Link("A", "B", lengthKm, List.of(new Core(0, List.of(), 320)), List.of());
        ModulationFormat mod = new ModulationFormat("4QAM", 5000, 4, 8.95, -19.03, 32, 0.1);
        NetworkTopology topology = new NetworkTopology(List.of(a, b), List.of(link), List.of(mod));
        return new Net(new ControlPlane(topology, null, SLOT, 0, cfg), link, a, b, mod);
    }

    // Independent re-derivation of the constants (not calling the production helpers).
    private static final double ALPHA = 0.2 / (10 * Math.log10(Math.E) * 1000);          // 1/m
    private static final double C = 299792458.0;
    private static final double LAMBDA = C / 1.9385E14;
    private static final double BETA2 = 1.6E-5 * LAMBDA * LAMBDA / (2 * Math.PI * C);     // s^2/m
    private static final double GAMMA = 0.0013;                                            // 1/(W m)

    private static double asinh(double x) {
        return Math.log(x + Math.sqrt(x * x + 1));
    }

    @Test
    @DisplayName("L9-b: single-channel NLI (SCI) equals the GN closed form (Poggiolini) and is comparable to ASE")
    void selfChannelInterferenceMagnitude() {
        PhysicalLayerConfig cfg = config(0.0, true);
        Net n = net(80.0, cfg);                     // exactly 1 span
        double bandwidth = 4 * SLOT;                // 50 GHz
        double g = 1E-3 / bandwidth;                // PSD at 0 dBm

        // (8/27) gamma^2 G^3 Leff^2 asinh(pi^2/2 |b2| Leff,a B^2) / (pi |b2| Leff,a)
        double leff = (1 - Math.exp(-ALPHA * 80e3)) / ALPHA;
        double leffA = 1 / ALPHA;
        double gn = (8.0 / 27) * GAMMA * GAMMA * g * g * g * leff * leff
                * asinh(Math.PI * Math.PI / 2 * BETA2 * leffA * bandwidth * bandwidth) / (Math.PI * BETA2 * leffA);
        double sci = PhysicalLayerModel.selfChannelInterference(n.link(), cfg, bandwidth);
        assertEquals(gn, sci, gn * 1E-9);

        // Before the CR-01 fix NLI/ASE was ~1e-11; with 0 dBm per 50 GHz channel it must be comparable to ASE.
        double nliOverAse = sci / n.link().getStaticAseNoise();
        assertTrue(nliOverAse > 1E-2 && nliOverAse < 10, "NLI/ASE out of the physical range: " + nliOverAse);
    }

    @Test
    @DisplayName("L9-b: for contiguous equal channels, SCI + XCI tends to the SCI of the whole occupied band")
    void crossChannelTermIsConsistentWithSelfChannelTerm() {
        PhysicalLayerConfig cfg = config(0.0, true);
        Net n = net(80.0, cfg);
        int width = 4;
        int channelsPerSide = 30;
        int victimStart = 150;
        double[] cache = new double[320];
        for (int k = -channelsPerSide; k <= channelsPerSide; k++) {
            if (k == 0) continue;
            int s = victimStart + k * width;
            double[] m = PhysicalLayerModel.generateNliMask(n.link(), cfg,
                    new Circuit("c" + k, n.a(), n.b(), List.of(n.link()), List.of(0), s, s + width - 1, n.mod(), 100.0), 320);
            for (int i = 0; i < cache.length; i++) cache[i] += m[i];
        }
        double b = width * SLOT;
        double g = 1E-3 / b;
        double avg = 0;
        for (int i = victimStart; i < victimStart + width; i++) avg += cache[i] / width;
        double total = PhysicalLayerModel.selfChannelInterference(n.link(), cfg, b) + g * avg;
        // Whole band has the same PSD g: reference = mu g^3 asinh(rho B_tot^2) = SCI(B_tot) scaled to the same PSD
        double bTot = (2 * channelsPerSide + 1) * b;
        double sciTotSamePsd = PhysicalLayerModel.selfChannelInterference(n.link(), cfg, bTot) * Math.pow(bTot / b, 3);
        assertEquals(sciTotSamePsd, total, sciTotSamePsd * 0.05);
    }

    @Test
    @DisplayName("L9-c: SNR vs launch power has an interior optimum where NLI = ASE / 2")
    void optimumLaunchPower() {
        double bestPower = Double.NaN;
        double bestSnr = -1;
        for (double p = -10.0; p <= 10.0 + 1E-9; p += 0.05) {
            Net n = net(800.0, config(p, true));    // 10 spans, isolated channel of 4 slots
            double snr = PhysicalLayerModel.predictSNR(n.cp(), new Path(List.of(n.link())), 0, 100, 103, n.mod(), 100.0);
            if (snr > bestSnr) {
                bestSnr = snr;
                bestPower = p;
            }
        }
        assertTrue(bestPower > -10 && bestPower < 10, "optimum must be interior, got " + bestPower + " dBm");

        // Analytic optimum for SNR = (P/B) / (ASE + eta P^3): eta P_opt^3 = ASE / 2.
        PhysicalLayerConfig cfg1mW = config(0.0, true);
        Net n = net(800.0, cfg1mW);
        double eta = PhysicalLayerModel.selfChannelInterference(n.link(), cfg1mW, 4 * SLOT) / 1E-9; // per W^3
        double pOptDbm = 10 * Math.log10(Math.cbrt(n.link().getStaticAseNoise() / (2 * eta)) / 1E-3);
        assertEquals(pOptDbm, bestPower, 0.1);
    }

    @Test
    @DisplayName("L9-d: an established neighbour lowers the SNR by exactly its cross-channel NLI, decaying with distance")
    void crossChannelInterference() {
        PhysicalLayerConfig cfg = config(0.0, true);
        double[] snrWithNeighbourAt = new double[3];
        int[] neighbourStart = {104, 120, 200};
        for (int k = 0; k < neighbourStart.length; k++) {
            Net n = net(400.0, cfg);
            Path victimPath = new Path(List.of(n.link()));
            double alone = PhysicalLayerModel.predictSNR(n.cp(), victimPath, 0, 100, 103, n.mod(), 100.0);
            int s = neighbourStart[k];
            n.cp().establishCircuit(new Circuit("x", n.a(), n.b(), List.of(n.link()), List.of(0), s, s + 3, n.mod(), 100.0));
            snrWithNeighbourAt[k] = PhysicalLayerModel.predictSNR(n.cp(), victimPath, 0, 100, 103, n.mod(), 100.0);
            assertTrue(snrWithNeighbourAt[k] < alone, "a neighbour must degrade the SNR");

            // Exact decomposition: 1/SNR_with - 1/SNR_alone = (I_ch * avg(cache)) / I_ch = avg(cache)
            double[] mask = PhysicalLayerModel.generateNliMask(n.link(), cfg,
                    new Circuit("y", n.a(), n.b(), List.of(n.link()), List.of(0), s, s + 3, n.mod(), 100.0), 320);
            double avg = (mask[100] + mask[101] + mask[102] + mask[103]) / 4;
            assertEquals(avg, 1 / snrWithNeighbourAt[k] - 1 / alone, avg * 1E-6);
        }
        assertTrue(snrWithNeighbourAt[0] < snrWithNeighbourAt[1] && snrWithNeighbourAt[1] < snrWithNeighbourAt[2],
                "XCI must decrease as the neighbour moves away");
    }
}
