package com.snets2.rmsca;

import com.snets2.config.PhysicalLayerConfig;
import com.snets2.metrics.PhysicalLayerModel;
import com.snets2.metrics.BlockingCause;
import com.snets2.model.*;
import com.snets2.rmsca.core.ICoreAssignment;
import com.snets2.rmsca.modulation.IModulationSelection;
import com.snets2.rmsca.modulation.ModulationResult;
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
 * <p>Execution sequence: Routing -> Modulation Loop (descending efficiency) 
 * -> Core Loop (strategy-dependent order) -> Spectrum -> QoT Validation.</p>
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

        // Default blocking cause
        cp.setLastBlockingCause(BlockingCause.OTHER);
        cp.setLastBlockingCoreId(null);

        // 1. Hardware check
        if (!source.hasAvailableTx()) {
            cp.setLastBlockingCause(BlockingCause.LACK_OF_TRANSMITTERS);
            return null;
        }
        if (!destination.hasAvailableRx()) {
            cp.setLastBlockingCause(BlockingCause.LACK_OF_RECEIVERS);
            return null;
        }

        // 2. Routing
        List<Path> candidatePaths = routing.findPaths(cp, source, destination);
        if (candidatePaths.isEmpty()) {
            cp.setLastBlockingCause(BlockingCause.NO_PATH);
            return null;
        }
        Path path = candidatePaths.get(0);

        // 3. Modulation Loop (Interleaved with Core, Spectrum and QoT)
        // Sort available modulations by spectral efficiency (M) descending
        List<ModulationFormat> availableModulations = cp.getTopology().modulations().stream()
            .sorted(Comparator.comparingDouble(ModulationFormat::m).reversed())
            .toList();

        PhysicalLayerConfig physConfig = cp.getPhysicalLayerConfig();
        boolean checkQoT = physConfig != null && physConfig.activeQoT();

        boolean foundPathAndMod = false;
        boolean foundFreeSlots = false;
        Integer lastAttemptedCore = null;

        for (ModulationFormat mod : availableModulations) {
            // a. Distance check
            if (path.getLength() > mod.maxReach()) continue;

            foundPathAndMod = true;

            // b. Calculate slots required
            int bitsPerSymbol = mod.getBitsPerSymbol();
            int numSlots = (int) Math.ceil((bitRate * 1E9) / (bitsPerSymbol * cp.getSlotBandwidth()));
            numSlots += cp.getGuardBand();

            // c. Iterate through candidate Cores provided by the strategy
            List<Integer> candidateCores = coreAssignment.selectCores(cp, path);
            for (Integer coreId : candidateCores) {
                lastAttemptedCore = coreId;

                // d. Spectrum Assignment
                SpectrumInterval slots = spectrumAssignment.findSlots(cp, path, coreId, numSlots);
                if (slots == null) continue;

                foundFreeSlots = true;

                // e. QoT Validation
                if (checkQoT) {
                    double snr = PhysicalLayerModel.predictSNR(cp, path, coreId, slots.start(), slots.end(), mod, bitRate);
                    if (snr < mod.getSnrThresholdLinear()) {
                        // Check if it would pass without crosstalk to isolate the cause
                        if (physConfig.activeXT()) {
                            double snrNoXt = PhysicalLayerModel.predictSnrWithoutXt(cp, path, coreId, slots.start(), slots.end(), mod, bitRate);
                            if (snrNoXt >= mod.getSnrThresholdLinear()) {
                                cp.setLastBlockingCause(BlockingCause.CROSSTALK);
                                cp.setLastBlockingCoreId(coreId);
                                continue;
                            }
                        }
                        cp.setLastBlockingCause(BlockingCause.QOT_NEW);
                        cp.setLastBlockingCoreId(coreId);
                        continue; // Try next core or modulation
                    }
                }

                // f. Success: Return Solution
                List<Integer> coreIndices = new ArrayList<>();
                for (int i = 0; i < path.links().size(); i++) {
                    coreIndices.add(coreId);
                }

                return new AllocationSolution(
                    source, destination, path.links(), coreIndices, 
                    slots.start(), slots.end(), mod, bitRate
                );
            }
        }

        // Set failure cause if not already set specifically by QoT
        if (!foundPathAndMod) {
            cp.setLastBlockingCause(BlockingCause.NO_PATH);
        } else if (!foundFreeSlots) {
            cp.setLastBlockingCause(BlockingCause.FRAGMENTATION);
            cp.setLastBlockingCoreId(lastAttemptedCore);
        }

        return null;
    }
}
