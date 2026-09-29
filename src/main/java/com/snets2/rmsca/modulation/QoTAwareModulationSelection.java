package com.snets2.rmsca.modulation;

import com.snets2.config.PhysicalLayerConfig;
import com.snets2.metrics.PhysicalLayerModel;
import com.snets2.model.ControlPlane;
import com.snets2.model.Link;
import com.snets2.model.ModulationFormat;
import com.snets2.rmsca.routing.Path;
import java.util.Comparator;
import java.util.List;

/**
 * QoT-aware modulation selection (id {@code qot-adaptive}): the most spectrally efficient format whose
 * quality of transmission is acceptable for the new circuit <b>and</b> for the circuits already established,
 * as predicted by the physical layer model (ASE + NLI + XT), regardless of {@code maxRange}.
 *
 * <p>The policy only orders the formats (spectral efficiency descending) and disables the reach filter
 * ({@link #enforcesReach} returns false). The feasibility decision is taken by the QoT validation of the
 * integrated RMSCA: for each path, the formats are tried in this order and, for each format, every core and
 * the interval chosen by the spectrum assignment are tried before the next (more robust) format; the first
 * candidate that passes the SNR/XT check of the new circuit and of the active circuits is accepted.</p>
 *
 * <p>When the QoT is disabled ({@code activeQoT = false} or no physical layer) there is no physical criterion
 * and the policy falls back to the reach: it then behaves exactly as {@link DistanceAdaptiveModulationSelection}.</p>
 */
public class QoTAwareModulationSelection implements IModulationSelection {

    /**
     * Upper bound used outside the integrated RMSCA: the most efficient format whose SNR on the path would be
     * acceptable for an isolated circuit (ASE of the amplifiers and self-channel NLI only, no neighbour). With the
     * network loaded the RMSCA may have to pick a more robust format. Returns {@code null} if no format is viable.
     */
    @Override
    public ModulationResult selectModulation(ControlPlane cp, Path path, double bitRate) {
        if (enforcesReach(cp)) return new DistanceAdaptiveModulationSelection().selectModulation(cp, path, bitRate);
        for (ModulationFormat format : byEfficiencyDescending(cp)) {
            int numSlots = SlotCalculator.requiredSlots(bitRate, format, cp);
            if (isolatedSnr(cp, path, numSlots) >= format.getSnrThresholdLinear()) {
                return new ModulationResult(format, numSlots);
            }
        }
        return null;
    }

    /** All formats by spectral efficiency (M) descending; the QoT validation of the RMSCA decides feasibility. */
    @Override
    public List<ModulationFormat> candidateFormats(ControlPlane cp, Path path, double bitRate) {
        return byEfficiencyDescending(cp);
    }

    /** The reach does not filter the formats while the QoT is active; without QoT it is the only criterion. */
    @Override
    public boolean enforcesReach(ControlPlane cp) {
        PhysicalLayerConfig config = cp.getPhysicalLayerConfig();
        return config == null || !config.activeQoT();
    }

    /** SNR (linear) of a circuit of {@code numSlots} slots alone on the path: ASE + self-channel interference. */
    static double isolatedSnr(ControlPlane cp, Path path, int numSlots) {
        PhysicalLayerConfig config = cp.getPhysicalLayerConfig();
        double bandwidth = PhysicalLayerModel.signalBandwidth(0, numSlots - 1, cp.getGuardBand(), cp.getSlotBandwidth());
        double power = PhysicalLayerModel.circuitLaunchPower(config, bandwidth);
        double noise = 0;
        for (Link link : path.links()) {
            noise += config.typeOfAmplifierGain() == PhysicalLayerConfig.AMP_GAIN_SATURATED
                    ? PhysicalLayerModel.calculateLinkAse(link, config, power)
                    : link.getStaticAseNoise();
            noise += PhysicalLayerModel.selfChannelInterference(link, config, bandwidth);
        }
        return power / bandwidth / Math.max(1E-30, noise);
    }

    private static List<ModulationFormat> byEfficiencyDescending(ControlPlane cp) {
        return cp.getTopology().modulations().stream()
            .sorted(Comparator.comparingDouble(ModulationFormat::m).reversed())
            .toList();
    }
}
