package com.snets2.rmsca;

import com.snets2.model.ModulationFormat;
import com.snets2.model.NetworkTopology;
import java.util.Comparator;
import java.util.List;

/**
 * Modulation selection based on path distance.
 * It picks the format with the highest spectral efficiency that still reaches the destination.
 */
public class DistanceAdaptiveModulationSelection implements IModulationSelection {
    private final List<ModulationFormat> availableModulations;
    private final double slotBandwidth; // amplitude of one slot in Hz

    public DistanceAdaptiveModulationSelection(List<ModulationFormat> modulations, double slotBandwidth) {
        // Sort modulations by spectral efficiency (M) descending
        this.availableModulations = modulations.stream()
            .sorted(Comparator.comparingDouble(ModulationFormat::m).reversed())
            .toList();
        this.slotBandwidth = slotBandwidth;
    }

    @Override
    public ModulationResult selectModulation(Path path, double bitRate) {
        double distance = path.getLength();

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
