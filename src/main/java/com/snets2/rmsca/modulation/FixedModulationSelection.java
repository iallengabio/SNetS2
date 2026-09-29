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
        ModulationFormat fixedFormat = fixedFormat(cp);
        if (fixedFormat == null) return null;
        int numSlots = SlotCalculator.requiredSlots(bitRate, fixedFormat, cp.getSlotBandwidth(), cp.getGuardBand());
        return new ModulationResult(fixedFormat, numSlots);
    }

    @Override
    public List<ModulationFormat> candidateFormats(ControlPlane cp, Path path, double bitRate) {
        ModulationFormat fixedFormat = fixedFormat(cp);
        return fixedFormat == null ? List.of() : List.of(fixedFormat);
    }

    private static ModulationFormat fixedFormat(ControlPlane cp) {
        List<ModulationFormat> available = cp.getTopology().modulations();
        if (available.isEmpty()) return null;
        for (ModulationFormat format : available) {
            if (format.name().equalsIgnoreCase("bpsk")) {
                return format;
            }
        }
        return available.get(0);
    }
}
