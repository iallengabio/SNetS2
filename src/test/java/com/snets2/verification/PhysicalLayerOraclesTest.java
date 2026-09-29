package com.snets2.verification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.snets2.config.PhysicalLayerConfig;
import com.snets2.metrics.PhysicalLayerModel;
import com.snets2.model.*;
import com.snets2.rmsca.routing.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression version of the physical layer checks of the V&amp;V campaign
 * (docs/review/04_relatorio_verificacao_validacao.md, E7a and E7b): the simulator is compared with formulas
 * re-derived here from first principles, not with its own helper functions.
 */
class PhysicalLayerOraclesTest {

    private static final double H = 6.626E-34, NU = 1.9385E14, NF_DB = 5.0, SPAN = 80.0, ALPHA_DB = 0.2, LSSS = 5.0;
    private static final double GAMMA = 1.3E-3, D = 1.6E-5, SLOT = 12.5E9;

    private static PhysicalLayerConfig config(double powerDbm) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("activeQoT", true); p.put("activeASE", true); p.put("activeNLI", true); p.put("activeXT", false);
        p.put("power", powerDbm); p.put("spanLength", SPAN); p.put("fiberLoss", ALPHA_DB);
        p.put("fiberNonlinearity", GAMMA); p.put("fiberDispersion", D); p.put("centerFrequency", NU);
        p.put("constantOfPlanck", H); p.put("noiseFigureOfOpticalAmplifier", NF_DB); p.put("amplificationFrequency", NU);
        p.put("switchInsertionLoss", LSSS); p.put("polarizationModes", 2.0); p.put("guardBand", 0);
        p.put("bvtSpectralWidth", SLOT);
        return new ObjectMapper().convertValue(p, PhysicalLayerConfig.class);
    }

    private static ControlPlane singleLink(double km, PhysicalLayerConfig cfg) {
        Node a = new Node("A", 10, 10, 0);
        Node b = new Node("B", 10, 10, 0);
        Link link = new Link("A", "B", km, List.of(new Core(0, List.of(), 320)), List.of());
        ModulationFormat mod = new ModulationFormat("4QAM", 5000, 4, 5.92, -16, 32, 0.1);
        return new ControlPlane(new NetworkTopology(List.of(a, b), List.of(link), List.of(mod)), null, SLOT, 0, cfg);
    }

    private static double lin(double db) { return Math.pow(10, db / 10); }

    /** Booster (3 Lsss) + ceil(L/span - 1) line amplifiers (alpha span) + pre-amplifier (alpha last segment). */
    private static double chainAse(double km) {
        int nLine = (int) Math.max(0, Math.ceil(km / SPAN - 1));
        double last = km - nLine * SPAN;
        double perGain = lin(NF_DB) * H * NU;
        return perGain * (lin(3 * LSSS) - 1) + nLine * perGain * (lin(ALPHA_DB * SPAN) - 1)
                + perGain * (lin(ALPHA_DB * last) - 1);
    }

    /** GN closed form: I_NLI = eta I^3 for an isolated channel of bandwidth b over ceil(L/span) incoherent spans. */
    private static double gnEta(double km, double b) {
        double alpha = ALPHA_DB / (10 * Math.log10(Math.E) * 1000);
        double c = 299792458.0;
        double lambda = c / NU;
        double beta2 = D * lambda * lambda / (2 * Math.PI * c);
        double leff = (1 - Math.exp(-alpha * SPAN * 1000)) / alpha;
        double leffA = 1 / alpha;
        double mu = (8.0 / 27.0) * GAMMA * GAMMA * leff * leff / (Math.PI * beta2 * leffA);
        double rho = Math.PI * Math.PI / 2 * beta2 * leffA;
        double x = rho * b * b;
        return Math.max(1, Math.ceil(km / SPAN)) * mu * Math.log(x + Math.sqrt(x * x + 1));
    }

    @Test
    @DisplayName("E7a: link ASE equals the booster + line + pre-amplifier chain computed by hand")
    void aseMatchesAmplifierChain() {
        for (double km : new double[] {20, 80, 100, 160, 161, 999, 2000, 4000}) {
            Link link = singleLink(km, config(0)).getLinks().get(0);
            double expected = chainAse(km);
            assertEquals(expected, link.getStaticAseNoise(), expected * 1E-12, "ASE at " + km + " km");
        }
    }

    @Test
    @DisplayName("E7b: SNR = I / (S_ASE + eta I^3) and the optimum launch power is (S_ASE / 2 eta)^(1/3)")
    void optimumLaunchPowerMatchesGnClosedForm() {
        double b = 4 * SLOT;
        for (int spans : new int[] {1, 10, 40}) {
            double km = spans * SPAN;
            double ase = chainAse(km);
            double eta = gnEta(km, b);
            double iOpt = Math.cbrt(ase / (2 * eta));
            double pOptDbm = 10 * Math.log10(iOpt * b / 1E-3);
            double snrMaxDb = 10 * Math.log10(iOpt / (1.5 * ase));

            // SNR predicted by the simulator at the analytical optimum
            double atOptimum = snrDb(km, pOptDbm);
            assertEquals(snrMaxDb, atOptimum, 1E-6, "SNR at the analytical optimum, " + spans + " spans");
            // no other launch power does better, and the slopes are +1 / -2 dB per dB far from the optimum
            for (double delta : new double[] {-3, -1, -0.1, 0.1, 1, 3}) {
                assertTrue(snrDb(km, pOptDbm + delta) < atOptimum, "optimum is a maximum, " + spans + " spans");
            }
            double lowSlope = snrDb(km, pOptDbm - 20) - snrDb(km, pOptDbm - 21);
            double highSlope = snrDb(km, pOptDbm + 21) - snrDb(km, pOptDbm + 20);
            assertEquals(1.0, lowSlope, 0.01, "ASE-limited slope");
            assertEquals(-2.0, highSlope, 0.01, "NLI-limited slope");
        }
    }

    private static double snrDb(double km, double powerDbm) {
        ControlPlane cp = singleLink(km, config(powerDbm));
        return 10 * Math.log10(PhysicalLayerModel.predictSNR(cp, new Path(cp.getLinks()), 0, 0, 3, null, 0));
    }
}
