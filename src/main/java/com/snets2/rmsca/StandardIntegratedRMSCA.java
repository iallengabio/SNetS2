package com.snets2.rmsca;

import com.snets2.model.*;
import com.snets2.rmsca.core.ICoreAssignment;
import com.snets2.rmsca.modulation.IModulationSelection;
import com.snets2.rmsca.modulation.ModulationResult;
import com.snets2.rmsca.routing.IRouting;
import com.snets2.rmsca.routing.Path;
import com.snets2.rmsca.spectrum.ISpectrumAssignment;
import com.snets2.rmsca.spectrum.SpectrumInterval;
import java.util.ArrayList;
import java.util.List;

/**
 * A standard sequential implementation of RMSCA.
 * It combines Routing, Modulation, Core, and Spectrum sub-algorithms.
 */
public class StandardIntegratedRMSCA implements IRMSCA {

    private IRouting routing;
    private ICoreAssignment coreAssignment;
    private IModulationSelection modulationSelection;
    private ISpectrumAssignment spectrumAssignment;

    public void setRouting(IRouting routing) { this.routing = routing; }
    public void setCoreAssignment(ICoreAssignment coreAssignment) { this.coreAssignment = coreAssignment; }
    public void setModulationSelection(IModulationSelection modulationSelection) { this.modulationSelection = modulationSelection; }
    public void setSpectrumAssignment(ISpectrumAssignment spectrumAssignment) { this.spectrumAssignment = spectrumAssignment; }

    @Override
    public AllocationSolution allocate(ControlPlane cp, Node source, Node destination, double bitRate) {
        if (routing == null || coreAssignment == null || modulationSelection == null || spectrumAssignment == null) {
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

        // 3. Modulation Selection
        ModulationResult modResult = modulationSelection.selectModulation(cp, path, bitRate);
        if (modResult == null) return null;

        // 4. Core Assignment
        Integer coreId = coreAssignment.selectCore(cp, path);
        if (coreId == null) return null;

        // 5. Spectrum Assignment
        SpectrumInterval slots = spectrumAssignment.findSlots(cp, path, coreId, modResult.numSlots());
        if (slots == null) return null;

        // 6. Return Solution
        List<Integer> coreIndices = new ArrayList<>();
        for (int i = 0; i < path.links().size(); i++) {
            coreIndices.add(coreId);
        }

        return new AllocationSolution(
            source, destination, path.links(), coreIndices, 
            slots.start(), slots.end(), modResult.format(), bitRate
        );
    }
}
