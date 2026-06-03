package com.snets2.rmsca;

import com.snets2.model.*;
import java.util.ArrayList;
import java.util.List;

/**
 * A standard sequential implementation of RMSCA.
 * It combines Routing, Modulation, Core, and Spectrum sub-algorithms.
 */
public class StandardIntegratedRMSCA implements IRMSCA {

    private IRouting routing = new DijkstraRouting();
    private ICoreAssignment coreAssignment = new FirstFitCoreAssignment();
    private ISpectrumAssignment spectrumAssignment = new FirstFitSpectrumAssignment();

    /**
     * Configures the spectrum assignment algorithm.
     */
    public void setSpectrumAssignment(ISpectrumAssignment spectrumAssignment) {
        this.spectrumAssignment = spectrumAssignment;
    }

    @Override
    public AllocationSolution allocate(ControlPlane cp, Node source, Node destination, double bitRate) {
        // 1. Hardware check
        if (!source.hasAvailableTx() || !destination.hasAvailableRx()) {
            return null;
        }

        // 2. Routing
        List<Path> candidatePaths = routing.findPaths(cp, source, destination);
        if (candidatePaths.isEmpty()) return null;
        Path path = candidatePaths.get(0);

        // 3. Modulation Selection
        IModulationSelection modulationSelection = new DistanceAdaptiveModulationSelection(
            cp.getTopology().modulations(), 
            cp.getSlotBandwidth()
        );
        ModulationResult modResult = modulationSelection.selectModulation(path, bitRate);
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
