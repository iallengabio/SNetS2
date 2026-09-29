package com.snets2.rmsca.modulation;

import com.snets2.config.PhysicalLayerConfig;
import com.snets2.model.ControlPlane;
import com.snets2.model.ModulationFormat;

/**
 * Single source of the "number of slots per request" rule used by the RMSCA, the modulation
 * selection policies and the relative-fragmentation metric.
 *
 * <p>{@code n = ceil(R * (1 + FEC) / (N_pol * log2(M) * f_slot)) + G}, with {@code R} the bit rate
 * (Gbps, converted to bit/s), {@code FEC} the FEC overhead fraction ({@code rateOfFEC}),
 * {@code N_pol} the number of polarization modes ({@code polarizationModes}), {@code f_slot} the slot
 * width (Hz) and {@code G} the guard band (slots). When {@code polarizationModes} is not configured
 * (0) a single polarization is assumed; a missing {@code rateOfFEC} means no FEC overhead.</p>
 */
public final class SlotCalculator {

    private SlotCalculator() {}

    /** Slots required on the given control plane (uses its slot width, guard band and physical config). */
    public static int requiredSlots(double bitRateGbps, ModulationFormat format, ControlPlane cp) {
        PhysicalLayerConfig config = cp.getPhysicalLayerConfig();
        double polarizationModes = config != null ? config.polarizationModes() : 0;
        double fecOverhead = config != null ? config.rateOfFEC() : 0;
        return requiredSlots(bitRateGbps, format, cp.getSlotBandwidth(), cp.getGuardBand(), polarizationModes, fecOverhead);
    }

    public static int requiredSlots(double bitRateGbps, ModulationFormat format, double slotBandwidthHz, int guardBand,
                                    double polarizationModes, double fecOverhead) {
        double nPol = polarizationModes > 0 ? polarizationModes : 1.0;
        double fec = Math.max(0.0, fecOverhead);
        double lineRate = bitRateGbps * 1E9 * (1.0 + fec);
        int bitsPerSymbol = format.getBitsPerSymbol();
        // The small epsilon avoids ceil(2.0000000000000004) = 3 from floating-point noise.
        return (int) Math.ceil(lineRate / (nPol * bitsPerSymbol * slotBandwidthHz) - 1E-9) + guardBand;
    }
}
