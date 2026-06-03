package com.snets2.rmsca;

import com.snets2.config.PhysicalLayerConfig;
import com.snets2.metrics.PhysicalLayerModel;
import com.snets2.model.*;
import com.snets2.rmsca.core.ICoreAssignment;
import com.snets2.rmsca.modulation.IModulationSelection;
import com.snets2.rmsca.routing.IRouting;
import com.snets2.rmsca.routing.Path;
import com.snets2.rmsca.spectrum.ISpectrumAssignment;
import com.snets2.rmsca.spectrum.SpectrumInterval;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A standard sequential implementation of RMSCA with physical layer awareness.
 * 
 * <p>Execution sequence: Routing -> Core -> Modulation Loop (descending efficiency) 
 * -> Spectrum -> QoT Validation (OSNR & XT).</p>
 */
public class StandardIntegratedRMSCA implements IRMSCA {

    private IRouting routing;
    private ICoreAssignment coreAssignment;
    private IModulationSelection modulationSelection; // Used to pick candidate modulations
    private ISpectrumAssignment spectrumAssignment;

    public void setRouting(IRouting routing) { this.routing = routing; }
    public void setCoreAssignment(ICoreAssignment coreAssignment) { this.coreAssignment = coreAssignment; }
    public void setModulationSelection(IModulationSelection modulationSelection) { this.modulationSelection = modulationSelection; }
    public void setSpectrumAssignment(ISpectrumAssignment spectrumAssignment) { this.spectrumAssignment = spectrumAssignment; }

    @Override
    public AllocationSolution allocate(ControlPlane cp, Node source, Node destination, double bitRate) {
        if (routing == null || coreAssignment == null || spectrumAssignment == null) {
            throw new IllegalStateException("StandardIntegratedRMSCA sub-algorithms not properly initialized.");
        }

        // 1. Hardware check
        if (!source.hasAvailableTx() || !destination.hasAvailableRx()) {
            return null;
        }

        // 2. Routing
        List<Path> candidatePaths = routing.findPaths(cp, source, destination);
        if (candidatePaths.isEmpty()) return null;
        Path path = candidatePaths.get(0);

        // 3. Core Assignment
        Integer coreId = coreAssignment.selectCore(cp, path);
        if (coreId == null) return null;

        // 4. Modulation Loop (Interleaved with Spectrum and QoT)
        // Sort available modulations by spectral efficiency (M) descending
        List<ModulationFormat> availableModulations = cp.getTopology().modulations().stream()
            .sorted(Comparator.comparingDouble(ModulationFormat::m).reversed())
            .toList();

        PhysicalLayerConfig physConfig = cp.getPhysicalLayerConfig();
        boolean checkQoT = physConfig != null && physConfig.activeQoT();

        for (ModulationFormat mod : availableModulations) {
            // a. Distance check
            if (path.getLength() > mod.maxReach()) continue;

            // b. Calculate slots required
            int bitsPerSymbol = mod.getBitsPerSymbol();
            int numSlots = (int) Math.ceil((bitRate * 1E9) / (bitsPerSymbol * cp.getSlotBandwidth()));
            numSlots += cp.getGuardBand();

            // c. Spectrum Assignment
            SpectrumInterval slots = spectrumAssignment.findSlots(cp, path, coreId, numSlots);
            if (slots == null) continue;

            // d. QoT Validation
            if (checkQoT) {
                double snr = PhysicalLayerModel.predictSNR(cp, path, coreId, slots.start(), slots.end(), mod, bitRate);
                if (snr < mod.getSnrThresholdLinear()) {
                    continue; // Modulation not viable for these slots, try next
                }
            }

            // e. Success: Return Solution
            List<Integer> coreIndices = new ArrayList<>();
            for (int i = 0; i < path.links().size(); i++) {
                coreIndices.add(coreId);
            }

            return new AllocationSolution(
                source, destination, path.links(), coreIndices, 
                slots.start(), slots.end(), mod, bitRate
            );
        }

        return null;
    }
}
