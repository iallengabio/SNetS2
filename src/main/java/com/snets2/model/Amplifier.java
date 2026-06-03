package com.snets2.model;

/**
 * Represents an optical amplifier (e.g., Erbium-Doped Fiber Amplifier - EDFA).
 * 
 * <p>Amplifiers are used in optical links to compensate for fiber attenuation.
 * While they restore signal power, they introduce Amplified Spontaneous Emission (ASE) noise,
 * which is a primary factor in Signal-to-Noise Ratio (SNR) degradation.</p>
 */
public class Amplifier {
    private final String id;
    private final double gain; // dB
    private final double noiseFigure; // dB
    private final double powerConsumption; // Watts
    private final double saturatedOutputPower; // dBm

    /**
     * Constructs an Amplifier with its technical specifications.
     *
     * @param id                   Unique identifier.
     * @param gain                 Signal gain provided by the amplifier in decibels.
     * @param noiseFigure          The noise figure (NF) in decibels, representing SNR degradation.
     * @param powerConsumption     Electrical power consumed in Watts.
     * @param saturatedOutputPower The maximum total output power the amplifier can provide.
     */
    public Amplifier(String id, double gain, double noiseFigure, double powerConsumption, double saturatedOutputPower) {
        this.id = id;
        this.gain = gain;
        this.noiseFigure = noiseFigure;
        this.powerConsumption = powerConsumption;
        this.saturatedOutputPower = saturatedOutputPower;
    }

    public String getId() { return id; }
    /** @return Gain in dB. */
    public double getGain() { return gain; }
    /** @return Noise Figure in dB. */
    public double getNoiseFigure() { return noiseFigure; }
    /** @return Electrical power consumption in Watts. */
    public double getPowerConsumption() { return powerConsumption; }
    /** @return Maximum output power in dBm. */
    public double getSaturatedOutputPower() { return saturatedOutputPower; }
}
