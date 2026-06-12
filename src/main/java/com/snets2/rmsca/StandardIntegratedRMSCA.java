package com.snets2.rmsca;

import com.snets2.config.PhysicalLayerConfig;
import com.snets2.metrics.PhysicalLayerModel;
import com.snets2.metrics.BlockingCause;
import com.snets2.model.*;
import com.snets2.rmsca.core.ICoreAssignment;
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
    private ISpectrumAssignment spectrumAssignment;
    private com.snets2.rmsca.regenerator.IRegeneratorAssignment regeneratorAssignment;

    public void setRouting(IRouting routing) { this.routing = routing; }
    public void setCoreAssignment(ICoreAssignment coreAssignment) { this.coreAssignment = coreAssignment; }
    public void setSpectrumAssignment(ISpectrumAssignment spectrumAssignment) { this.spectrumAssignment = spectrumAssignment; }
    public void setRegeneratorAssignment(com.snets2.rmsca.regenerator.IRegeneratorAssignment regeneratorAssignment) { this.regeneratorAssignment = regeneratorAssignment; }

    @Override
    public AllocationResult allocate(ControlPlane cp, Node source, Node destination, double bitRate) {
        if (routing == null || coreAssignment == null || spectrumAssignment == null) {
            throw new IllegalStateException("StandardIntegratedRMSCA sub-algorithms not properly initialized.");
        }

        // Default blocking cause tracked locally
        BlockingCause currentCause = BlockingCause.OTHER;
        Integer currentCoreId = null;

        // 1. Hardware check
        if (!source.hasAvailableTx()) {
            return new AllocationResult(source, destination, bitRate, BlockingCause.LACK_OF_TRANSMITTERS);
        }
        if (!destination.hasAvailableRx()) {
            return new AllocationResult(source, destination, bitRate, BlockingCause.LACK_OF_RECEIVERS);
        }

        // 2. Routing
        List<Path> candidatePaths = routing.findPaths(cp, source, destination);
        if (candidatePaths.isEmpty()) {
            return new AllocationResult(source, destination, bitRate, BlockingCause.NO_PATH);
        }

        PhysicalLayerConfig physConfig = cp.getPhysicalLayerConfig();
        boolean checkQoT = physConfig != null && physConfig.activeQoT();

        boolean foundPathAndMod = false;
        boolean foundFreeSlots = false;
        Integer lastAttemptedCore = null;

        for (Path path : candidatePaths) {
            // 3. Modulation Loop (Interleaved with Core, Spectrum and QoT)
            // Sort available modulations by spectral efficiency (M) descending
            List<ModulationFormat> availableModulations = cp.getTopology().modulations().stream()
                .sorted(Comparator.comparingDouble(ModulationFormat::m).reversed())
                .toList();

            for (ModulationFormat mod : availableModulations) {
                // a. Distance check (only bypass if no regenerator assignment is configured)
                if (path.getLength() > mod.maxReach() && regeneratorAssignment == null) continue;

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

                    List<Node> regens = List.of();
                    boolean reachViolated = path.getLength() > mod.maxReach();

                    if (reachViolated) {
                        if (regeneratorAssignment == null) continue;
                        regens = regeneratorAssignment.assignRegenerators(cp, path, coreId, mod, slots.start(), slots.end(), bitRate);
                        if (regens == null) continue;
                    }

                    // e. QoT Validation
                    if (checkQoT) {
                        double snr = PhysicalLayerModel.predictSNR(cp, path, regens, coreId, slots.start(), slots.end(), mod, bitRate);
                        if (snr < mod.getSnrThresholdLinear()) {
                            // If we haven't tried assigning regenerators yet, let's try now to see if they can fix the QoT
                            if (!reachViolated && regeneratorAssignment != null) {
                                regens = regeneratorAssignment.assignRegenerators(cp, path, coreId, mod, slots.start(), slots.end(), bitRate);
                                if (regens != null && !regens.isEmpty()) {
                                    snr = PhysicalLayerModel.predictSNR(cp, path, regens, coreId, slots.start(), slots.end(), mod, bitRate);
                                    if (snr >= mod.getSnrThresholdLinear()) {
                                        // Passed with regenerators!
                                        List<Integer> coreIndices = new ArrayList<>();
                                        for (int i = 0; i < path.links().size(); i++) {
                                            coreIndices.add(coreId);
                                        }
                                        return new AllocationResult(
                                            source, destination, path.links(), coreIndices, 
                                            slots.start(), slots.end(), mod, bitRate, regens
                                        );
                                    }
                                }
                            }

                            // Check if it would pass without crosstalk to isolate the cause
                            if (physConfig.activeXT()) {
                                double snrNoXt = PhysicalLayerModel.predictSnrWithoutXt(cp, path, regens, coreId, slots.start(), slots.end(), mod, bitRate);
                                if (snrNoXt >= mod.getSnrThresholdLinear()) {
                                    currentCause = BlockingCause.CROSSTALK;
                                    currentCoreId = coreId;
                                    continue;
                                }
                            }
                            currentCause = BlockingCause.QOT_NEW;
                            currentCoreId = coreId;
                            continue; // Try next core or modulation
                        }
                    }

                    // e2. Check QoT of other active circuits
                    boolean otherQotOk = true;
                    if (checkQoT && physConfig.activeQoTForOther()) {
                        applyTemporaryNoise(cp, path, coreId, slots.start(), slots.end(), mod, bitRate, regens);
                        for (Circuit activeCircuit : cp.getActiveCircuits()) {
                            double activeSnr = PhysicalLayerModel.predictSNR(
                                cp, new Path(activeCircuit.getPath()), activeCircuit.getRegeneratorNodes(),
                                activeCircuit.getCoreIndices().get(0), activeCircuit.getStartSlot(), activeCircuit.getEndSlot(),
                                activeCircuit.getModulation(), activeCircuit.getBitRate()
                            );
                            if (activeSnr < activeCircuit.getModulation().getSnrThresholdLinear()) {
                                otherQotOk = false;
                                currentCause = BlockingCause.QOT_OTHERS;
                                if (physConfig.activeXTForOther()) {
                                    double activeSnrNoXt = PhysicalLayerModel.predictSnrWithoutXt(
                                        cp, new Path(activeCircuit.getPath()), activeCircuit.getRegeneratorNodes(),
                                        activeCircuit.getCoreIndices().get(0), activeCircuit.getStartSlot(), activeCircuit.getEndSlot(),
                                        activeCircuit.getModulation(), activeCircuit.getBitRate()
                                    );
                                    if (activeSnrNoXt >= activeCircuit.getModulation().getSnrThresholdLinear()) {
                                        currentCause = BlockingCause.XT_OTHERS;
                                    }
                                }
                                break;
                            }
                        }
                        removeTemporaryNoise(cp, path, coreId, slots.start(), slots.end(), mod, bitRate, regens);
                    }

                    if (!otherQotOk) {
                        currentCoreId = coreId;
                        continue; // Try next core or modulation
                    }

                    // f. Success: Return Solution
                    List<Integer> coreIndices = new ArrayList<>();
                    for (int i = 0; i < path.links().size(); i++) {
                        coreIndices.add(coreId);
                    }

                    return new AllocationResult(
                        source, destination, path.links(), coreIndices, 
                        slots.start(), slots.end(), mod, bitRate, regens
                    );
                }
            }
        }

        // Set failure cause if not already set specifically by QoT
        if (!foundPathAndMod) {
            currentCause = BlockingCause.NO_PATH;
        } else if (!foundFreeSlots) {
            currentCause = BlockingCause.FRAGMENTATION;
            currentCoreId = lastAttemptedCore;
        }

        return new AllocationResult(source, destination, bitRate, currentCause, currentCoreId);
    }

    private void applyTemporaryNoise(ControlPlane cp, Path path, int coreId, int startSlot, int endSlot, ModulationFormat mod, double bitRate, List<Node> regens) {
        PhysicalLayerConfig physicalLayerConfig = cp.getPhysicalLayerConfig();
        if (physicalLayerConfig == null) return;
        
        Circuit tempCircuit = new Circuit("temp", cp.getNode(path.links().get(0).getSourceId()), 
                                          cp.getNode(path.links().get(path.links().size()-1).getDestinationId()), 
                                          path.links(), getCoreIndicesList(path.links().size(), coreId), 
                                          startSlot, endSlot, mod, bitRate, regens);
                                          
        for (int i = 0; i < tempCircuit.getPath().size(); i++) {
            Link link = tempCircuit.getPath().get(i);
            int coreIndex = tempCircuit.getCoreIndices().get(i);
            Core core = link.getCore(coreIndex);
            
            // NLI
            double[] nliMask = PhysicalLayerModel.generateNliMask(link, physicalLayerConfig, tempCircuit, core.getSpectrum().getNumSlots());
            for (int s = 0; s < nliMask.length; s++) {
                core.addNliNoise(s, nliMask[s]);
            }
            
            // XT
            double xtContribution = PhysicalLayerModel.calculateXtContribution(link, physicalLayerConfig, tempCircuit);
            for (int adjId : core.getAdjacentCores()) {
                Core adjCore = link.getCore(adjId);
                if (adjCore != null) {
                    for (int s = tempCircuit.getStartSlot(); s <= tempCircuit.getEndSlot(); s++) {
                        adjCore.addXtNoise(s, xtContribution);
                    }
                }
            }
        }
    }

    private void removeTemporaryNoise(ControlPlane cp, Path path, int coreId, int startSlot, int endSlot, ModulationFormat mod, double bitRate, List<Node> regens) {
        PhysicalLayerConfig physicalLayerConfig = cp.getPhysicalLayerConfig();
        if (physicalLayerConfig == null) return;
        
        Circuit tempCircuit = new Circuit("temp", cp.getNode(path.links().get(0).getSourceId()), 
                                          cp.getNode(path.links().get(path.links().size()-1).getDestinationId()), 
                                          path.links(), getCoreIndicesList(path.links().size(), coreId), 
                                          startSlot, endSlot, mod, bitRate, regens);
                                          
        for (int i = 0; i < tempCircuit.getPath().size(); i++) {
            Link link = tempCircuit.getPath().get(i);
            int coreIndex = tempCircuit.getCoreIndices().get(i);
            Core core = link.getCore(coreIndex);
            
            // NLI
            double[] nliMask = PhysicalLayerModel.generateNliMask(link, physicalLayerConfig, tempCircuit, core.getSpectrum().getNumSlots());
            for (int s = 0; s < nliMask.length; s++) {
                core.removeNliNoise(s, nliMask[s]);
            }
            
            // XT
            double xtContribution = PhysicalLayerModel.calculateXtContribution(link, physicalLayerConfig, tempCircuit);
            for (int adjId : core.getAdjacentCores()) {
                Core adjCore = link.getCore(adjId);
                if (adjCore != null) {
                    for (int s = tempCircuit.getStartSlot(); s <= tempCircuit.getEndSlot(); s++) {
                        adjCore.removeXtNoise(s, xtContribution);
                    }
                }
            }
        }
    }

    private List<Integer> getCoreIndicesList(int size, int coreId) {
        List<Integer> list = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            list.add(coreId);
        }
        return list;
    }
}
