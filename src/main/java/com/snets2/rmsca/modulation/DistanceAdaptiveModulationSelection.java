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
        double slotBandwidth = cp.getSlotBandwidth();
        
        // Sort modulations by spectral efficiency (M) descending
        List<ModulationFormat> availableModulations = cp.getTopology().modulations().stream()
            .sorted(Comparator.comparingDouble(ModulationFormat::m).reversed())
            .toList();

        for (ModulationFormat format : availableModulations) {
            if (distance <= format.maxReach()) {
                // Number of slots = ceil( bitRate / (bitsPerSymbol * slotBandwidth) )
                // Note: bitRate is in Gbps, slotBandwidth is in Hz (e.g. 12.5E9)
                // We need to match units. bitRate * 1E9 / (bitsPerSymbol * slotBandwidth)
                int bitsPerSymbol = format.getBitsPerSymbol();
                int numSlots = (int) Math.ceil((bitRate * 1E9) / (bitsPerSymbol * slotBandwidth));
                
                // Add guard band (simplified, should come from physical layer config)
                numSlots += 1; 

                return new ModulationResult(format, numSlots);
            }
        }

        return null;
    }
}
