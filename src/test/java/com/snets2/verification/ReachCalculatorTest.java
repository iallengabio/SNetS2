package com.snets2.verification;

import com.snets2.config.ConfigLoader;
import com.snets2.config.PhysicalLayerConfig;
import com.snets2.config.ScenarioSetup;
import com.snets2.model.ModulationFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks of the reach tool of issue #16 ({@link ReachCalculator}) and of the maxRange shipped in experiments/.
 */
class ReachCalculatorTest {

    private static final String[] EXPERIMENTS = {"experiment01", "experiment_all_metrics", "experiment_only_blocking"};
    private static final double STEP = 10.0;

    private static ScenarioSetup setup(String experiment) throws Exception {
        return ConfigLoader.load(new File("experiments/" + experiment + "/setup.json")).getBaseScenario();
    }

    @Test
    @DisplayName("Reach decreases with the modulation order, for every configured bit rate and in the worst case")
    void reachDecreasesWithModulationOrder() throws Exception {
        List<List<ReachCalculator.Reach>> table = ReachCalculator.compute(setup("experiment01"), null, STEP);
        for (int i = 1; i < table.size(); i++) {
            for (int r = 0; r < table.get(i).size(); r++) {
                assertTrue(table.get(i).get(r).reachKm() <= table.get(i - 1).get(r).reachKm(),
                        "reach must not grow with M: " + table.get(i).get(r) + " vs " + table.get(i - 1).get(r));
            }
            assertTrue(ReachCalculator.worst(table.get(i)).reachKm() < ReachCalculator.worst(table.get(i - 1)).reachKm());
        }
    }

    @Test
    @DisplayName("The shipped maxRange never exceeds the reach computed with the same model, FEC and grid")
    void shippedMaxRangeIsWithinComputedReach() throws Exception {
        for (String experiment : EXPERIMENTS) {
            ScenarioSetup setup = setup(experiment);
            List<ModulationFormat> formats = ReachCalculator.formats(setup);
            List<List<ReachCalculator.Reach>> table = ReachCalculator.compute(setup, null, STEP);
            for (int i = 0; i < formats.size(); i++) {
                double reach = ReachCalculator.worst(table.get(i)).reachKm();
                assertTrue(formats.get(i).maxReach() <= reach, experiment + " " + formats.get(i).name()
                        + ": maxRange " + formats.get(i).maxReach() + " > computed reach " + reach);
            }
        }
    }

    @Test
    @DisplayName("The reach is the last multiple of the step meeting the SNR threshold")
    void reachIsTheLastFeasibleGridPoint() throws Exception {
        ScenarioSetup setup = setup("experiment01");
        PhysicalLayerConfig cfg = setup.physicalLayer();
        int slots = setup.simulation().totalSlots();
        for (ModulationFormat mod : ReachCalculator.formats(setup)) {
            for (double bitRate : new double[] {100, 800}) {
                ReachCalculator.Reach r = ReachCalculator.reach(cfg, slots, mod, bitRate, STEP);
                assertTrue(r.reachKm() > 0 && r.reachKm() % STEP == 0, r.toString());
                assertTrue(ReachCalculator.referenceSnr(cfg, slots, mod, bitRate, r.reachKm()) >= mod.getSnrThresholdLinear());
                assertTrue(ReachCalculator.referenceSnr(cfg, slots, mod, bitRate, r.reachKm() + STEP) < mod.getSnrThresholdLinear());
            }
        }
    }

    @Test
    @DisplayName("The reference SNR is non-increasing with the length, and the loaded core reaches less than a lone channel")
    void referenceSnrBehaviour() throws Exception {
        ScenarioSetup setup = setup("experiment01");
        PhysicalLayerConfig cfg = setup.physicalLayer();
        ModulationFormat mod = ReachCalculator.formats(setup).get(2); // 16QAM
        double previous = Double.POSITIVE_INFINITY;
        for (double km = 10; km <= 3000; km += 10) {
            double snr = ReachCalculator.referenceSnr(cfg, 320, mod, 100, km);
            assertTrue(snr <= previous * (1 + 1E-12), "SNR grew at " + km + " km");
            previous = snr;
        }
        // A core with room for a single channel has no cross-channel NLI (the isolated case of V&V E7c).
        int n = com.snets2.rmsca.modulation.SlotCalculator.requiredSlots(100, mod, cfg.bvtSpectralWidth(),
                cfg.guardBand(), cfg.polarizationModes(), cfg.rateOfFEC());
        double loaded = ReachCalculator.reach(cfg, 320, mod, 100, STEP).reachKm();
        double isolated = ReachCalculator.reach(cfg, n, mod, 100, STEP).reachKm();
        assertTrue(loaded < isolated, "loaded " + loaded + " km vs isolated " + isolated + " km");
    }
}
