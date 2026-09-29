package com.snets2.rmsca.modulation;

import com.snets2.model.ControlPlane;
import com.snets2.model.ModulationFormat;
import com.snets2.rmsca.routing.Path;
import java.util.Comparator;
import java.util.List;

/**
 * Modulation selection based on path distance.
 * It picks the format with the highest spectral efficiency that still reaches the destination.
 */
public class DistanceAdaptiveModulationSelection implements IModulationSelection {

    @Override
    public ModulationResult selectModulation(ControlPlane cp, Path path, double bitRate) {
        double distance = path.getLength();
        for (ModulationFormat format : byEfficiencyDescending(cp)) {
            if (distance <= format.maxReach()) {
                int numSlots = SlotCalculator.requiredSlots(bitRate, format, cp);
                return new ModulationResult(format, numSlots);
            }
        }
        return null;
    }

    /**
     * All formats by spectral efficiency (M) descending. Combined with the reach check of the RMSCA,
     * the first feasible candidate is the most efficient format that reaches the destination, and the
     * less efficient ones are fallbacks when spectrum or QoT fail.
     */
    @Override
    public List<ModulationFormat> candidateFormats(ControlPlane cp, Path path, double bitRate) {
        return byEfficiencyDescending(cp);
    }

    private static List<ModulationFormat> byEfficiencyDescending(ControlPlane cp) {
        return cp.getTopology().modulations().stream()
            .sorted(Comparator.comparingDouble(ModulationFormat::m).reversed())
            .toList();
    }
}
