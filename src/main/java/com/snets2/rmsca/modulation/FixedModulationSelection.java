package com.snets2.rmsca.modulation;

import com.snets2.model.ControlPlane;
import com.snets2.model.ModulationFormat;
import com.snets2.rmsca.routing.Path;
import java.util.List;

/**
 * Fixed modulation selection policy.
 * It always selects a fixed modulation format (BPSK if available, otherwise the first format in the list)
 * regardless of path distance.
 */
public class FixedModulationSelection implements IModulationSelection {

    @Override
    public ModulationResult selectModulation(ControlPlane cp, Path path, double bitRate) {
        List<ModulationFormat> available = cp.getTopology().modulations();
        if (available.isEmpty()) return null;

        ModulationFormat fixedFormat = null;
        for (ModulationFormat format : available) {
            if (format.name().equalsIgnoreCase("bpsk")) {
                fixedFormat = format;
                break;
            }
        }

        if (fixedFormat == null) {
            fixedFormat = available.get(0);
        }

        int bitsPerSymbol = fixedFormat.getBitsPerSymbol();
        double slotBandwidth = cp.getSlotBandwidth();
        int numSlots = (int) Math.ceil((bitRate * 1E9) / (bitsPerSymbol * slotBandwidth));
        numSlots += cp.getGuardBand();

        return new ModulationResult(fixedFormat, numSlots);
    }
}
