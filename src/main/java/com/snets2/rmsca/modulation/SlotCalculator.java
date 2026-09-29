package com.snets2.rmsca.modulation;

import com.snets2.model.ModulationFormat;

/**
 * Single source of the "number of slots per request" rule used by the RMSCA, the modulation
 * selection policies and the relative-fragmentation metric.
 *
 * <p>{@code n = ceil(R / (log2(M) * f_slot)) + G}, with {@code R} the bit rate (Gbps, converted to
 * bit/s), {@code f_slot} the slot width (Hz) and {@code G} the guard band (slots). Polarization
 * multiplexing and FEC overhead are not modelled (see CR-10 in docs/review/02_code_review.md).</p>
 */
public final class SlotCalculator {

    private SlotCalculator() {}

    public static int requiredSlots(double bitRateGbps, ModulationFormat format, double slotBandwidthHz, int guardBand) {
        int bitsPerSymbol = format.getBitsPerSymbol();
        return (int) Math.ceil((bitRateGbps * 1E9) / (bitsPerSymbol * slotBandwidthHz)) + guardBand;
    }
}
