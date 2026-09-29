package com.snets2.rmsca.modulation;

import com.snets2.model.ControlPlane;
import com.snets2.rmsca.Configurable;
import com.snets2.rmsca.routing.Path;
import java.util.Map;
import java.util.Set;

/**
 * QoT-aware modulation selection with a margin (id {@code qot-margin}): on each path, the most spectrally efficient
 * format whose new circuit keeps an SNR margin of at least {@code sigma} dB above the threshold of the format; if no
 * candidate of the path keeps the margin, the most efficient feasible one ({@code qot-adaptive}).
 *
 * <p>Port of {@code ModulationSelectionByQoTAndSigma} of SNetS v1 (parameter {@code sigma}), from the KSP-RQoTO
 * algorithm of A. Fontinele, I. Santos, J. N. Neto, D. R. Campelo and A. Soares, "An efficient IA-RMLSA algorithm for
 * transparent elastic optical networks", Computer Networks 118 (2017) 1-14, doi:10.1016/j.comnet.2017.03.003.
 * Extension not present in v1: an optional crosstalk margin {@code sigmaXt} (dB below the XT threshold of the format),
 * for SDM networks limited by inter-core crosstalk.</p>
 *
 * <p>Both margins apply to the new circuit only; the circuits already established must still satisfy their own
 * thresholds, as in {@code qot-adaptive}. With {@code sigma = sigmaXt = 0} the policy is identical to
 * {@code qot-adaptive}. Without QoT ({@code activeQoT = false}) there is no margin and the policy uses the reach.</p>
 */
public class QoTMarginModulationSelection extends QoTAwareModulationSelection implements Configurable {

    public static final String SIGMA = "sigma";
    public static final String SIGMA_XT = "sigmaXt";

    private double sigmaDb = 0;
    private double sigmaXtDb = 0;

    public QoTMarginModulationSelection() {}

    public QoTMarginModulationSelection(double sigmaDb, double sigmaXtDb) {
        configure(Map.of(SIGMA, sigmaDb, SIGMA_XT, sigmaXtDb));
    }

    @Override
    public Set<String> parameterNames() {
        return Set.of(SIGMA, SIGMA_XT);
    }

    @Override
    public void configure(Map<String, Object> parameters) {
        double sigma = Configurable.doubleParameter(parameters, SIGMA, 0);
        double sigmaXt = Configurable.doubleParameter(parameters, SIGMA_XT, 0);
        if (!(sigma >= 0) || Double.isInfinite(sigma)) {
            throw new IllegalArgumentException("simulation.algorithmParameters.sigma must be a finite value >= 0 dB, got " + sigma);
        }
        if (!(sigmaXt >= 0) || Double.isInfinite(sigmaXt)) {
            throw new IllegalArgumentException("simulation.algorithmParameters.sigmaXt must be a finite value >= 0 dB, got " + sigmaXt);
        }
        this.sigmaDb = sigma;
        this.sigmaXtDb = sigmaXt;
    }

    @Override
    public double snrMarginDb(ControlPlane cp) {
        return enforcesReach(cp) ? 0 : sigmaDb;
    }

    @Override
    public double xtMarginDb(ControlPlane cp) {
        return enforcesReach(cp) ? 0 : sigmaXtDb;
    }

    /**
     * Upper bound for an isolated circuit (see {@link QoTAwareModulationSelection#selectModulation}): the most
     * efficient format whose isolated SNR keeps the margin, otherwise the most efficient viable one.
     */
    @Override
    public ModulationResult selectModulation(ControlPlane cp, Path path, double bitRate) {
        if (enforcesReach(cp) || sigmaDb == 0) return super.selectModulation(cp, path, bitRate);
        double factor = Math.pow(10, sigmaDb / 10);
        for (var format : candidateFormats(cp, path, bitRate)) {
            int numSlots = SlotCalculator.requiredSlots(bitRate, format, cp);
            if (isolatedSnr(cp, path, numSlots) >= format.getSnrThresholdLinear() * factor) {
                return new ModulationResult(format, numSlots);
            }
        }
        return super.selectModulation(cp, path, bitRate);
    }
}
