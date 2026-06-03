package com.snets2.model;

/**
 * Defines a Modulation Format used in the optical network.
 * 
 * <p>Each format has specific spectral efficiency (bits per symbol) and tolerance 
 * to physical impairments.</p>
 */
public record ModulationFormat(
    String name,
    double maxReach,         // Maximum distance in km
    double m,                // Modulation order (M). bitsPerSymbol = log2(M)
    double snrThreshold,    // Minimum required SNR in dB
    double crosstalkThreshold, // Maximum tolerable inter-core Crosstalk in dB
    double symbolRate,       // Gbaud (spectral width per slot)
    double energyPerBit      // Joules per bit
) {
    /**
     * Returns the number of bits per symbol (spectral efficiency).
     * @return log2(M)
     */
    public int getBitsPerSymbol() {
        return (int) (Math.log(m) / Math.log(2));
    }

    /**
     * Calculates the raw bit rate provided by this modulation format.
     * @return Bit rate in Gbps.
     */
    public double getBitRate() {
        return getBitsPerSymbol() * symbolRate;
    }

    /** @return SNR threshold in linear scale. */
    public double getSnrThresholdLinear() {
        return Math.pow(10, snrThreshold / 10.0);
    }

    /** @return Crosstalk threshold in linear scale. */
    public double getCrosstalkThresholdLinear() {
        return Math.pow(10, crosstalkThreshold / 10.0);
    }
}
