package com.snets2.config;

/**
 * Physical layer parameters (JSON key {@code physicalLayer}).
 *
 * <p>Key names and default values follow SNetS v1
 * (<a href="https://github.com/alexandrefontinele/SNetS-SDM-SBRC26">SNetS-SDM-SBRC26</a>,
 * {@code simulationControl.parsers.PhysicalLayerConfig}) so that v1 setups can be reused.
 * See {@code docs/formal_description/07_physical_layer_models.md} for the equations.</p>
 *
 * @param powerSaturationOfOpticalAmplifier Amplifier output saturation power P_sat (dBm).
 * @param noiseFactorModelParameterA1       A1 of the power-dependent noise factor model (dimensionless).
 * @param noiseFactorModelParameterA2       A2 of the power-dependent noise factor model (W).
 * @param typeOfAmplifierGain               {@link #AMP_GAIN_FIXED} or {@link #AMP_GAIN_SATURATED}.
 * @param switchInsertionLoss               Insertion loss of each ROADM element (demux, switch, mux) in dB.
 * @param fixedPowerSpectralDensity         If true, every circuit is launched with the same PSD
 *                                          ({@code power} over {@code referenceBandwidthForPowerSpectralDensity}).
 * @param referenceBandwidthForPowerSpectralDensity Reference bandwidth B_ref (Hz) used when the PSD is fixed.
 */
public record PhysicalLayerConfig(
    boolean activeQoT,
    boolean activeQoTForOther,
    boolean activeASE,
    boolean activeNLI,
    boolean activeXT,
    boolean activeXTForOther,
    double rateOfFEC,
    double power,
    double spanLength,
    double fiberLoss,
    double fiberNonlinearity,
    double fiberDispersion,
    double centerFrequency,
    double constantOfPlanck,
    double noiseFigureOfOpticalAmplifier,
    double powerSaturationOfOpticalAmplifier,
    double noiseFactorModelParameterA1,
    double noiseFactorModelParameterA2,
    int typeOfAmplifierGain,
    double amplificationFrequency,
    double switchInsertionLoss,
    boolean fixedPowerSpectralDensity,
    double referenceBandwidthForPowerSpectralDensity,
    double propagationConstant,
    double bendingRadius,
    double couplingCoefficient,
    double corePitch,
    double polarizationModes,
    int guardBand,
    double bvtSpectralWidth
) {
    /** Amplifier gain equals its nominal (unsaturated) value, independent of load. */
    public static final int AMP_GAIN_FIXED = 0;
    /** Amplifier gain compresses with the total input power (gain saturation). */
    public static final int AMP_GAIN_SATURATED = 1;

    public PhysicalLayerConfig {
        if (typeOfAmplifierGain != AMP_GAIN_FIXED && typeOfAmplifierGain != AMP_GAIN_SATURATED) {
            throw new IllegalArgumentException("typeOfAmplifierGain must be 0 (fixed) or 1 (saturated), got " + typeOfAmplifierGain);
        }
        if (fixedPowerSpectralDensity && referenceBandwidthForPowerSpectralDensity <= 0) {
            throw new IllegalArgumentException("referenceBandwidthForPowerSpectralDensity must be > 0 when fixedPowerSpectralDensity is true");
        }
    }

    /**
     * Number of polarization modes, defaulting to 2 (dual polarization) when unset, as in SNetS v1.
     */
    public double effectivePolarizationModes() {
        return polarizationModes > 0 ? polarizationModes : 2.0;
    }
}
