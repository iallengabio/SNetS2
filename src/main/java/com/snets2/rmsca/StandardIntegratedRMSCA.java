package com.snets2.rmsca;

import com.snets2.config.PhysicalLayerConfig;
import com.snets2.metrics.PhysicalLayerModel;
import com.snets2.metrics.BlockingCause;
import com.snets2.model.*;
import com.snets2.rmsca.core.ICoreAssignment;
import com.snets2.rmsca.modulation.DistanceAdaptiveModulationSelection;
import com.snets2.rmsca.modulation.IModulationSelection;
import com.snets2.rmsca.modulation.SlotCalculator;
import com.snets2.rmsca.routing.IRouting;
import com.snets2.rmsca.routing.Path;
import com.snets2.rmsca.spectrum.ISpectrumAssignment;
import com.snets2.rmsca.spectrum.SpectrumInterval;
import java.util.ArrayList;
import java.util.List;

/**
 * A standard sequential implementation of RMSCA with physical layer awareness.
 *
 * <p>Execution sequence: Tx/Rx check -> Routing -> for each pass (transparent, then regenerated) ->
 * Path loop -> Modulation loop (order given by {@link IModulationSelection}) -> Core loop
 * (strategy-dependent order) -> Spectrum -> [Regenerator placement] -> QoT validation.</p>
 *
 * <p><b>Pass 1 (transparent)</b> only tries formats whose {@code maxReach} covers the path, without
 * regenerators. <b>Pass 2</b> runs only if pass 1 failed and a regenerator assignment is configured:
 * it retries every format with regenerators placed by the assignment. Hence a transparent solution
 * with a less efficient format is always preferred to a regenerated one.</p>
 *
 * <p>Every candidate is validated by the same {@link #evaluate} step: SNR of the new circuit, its
 * crosstalk against the modulation threshold, and the SNR/crosstalk of the circuits already active.</p>
 */
public class StandardIntegratedRMSCA implements IRMSCA {

    private IRouting routing;
    private ICoreAssignment coreAssignment;
    private ISpectrumAssignment spectrumAssignment;
    private com.snets2.rmsca.regenerator.IRegeneratorAssignment regeneratorAssignment;
    private IModulationSelection modulationSelection = new DistanceAdaptiveModulationSelection();

    public void setRouting(IRouting routing) { this.routing = routing; }
    public ICoreAssignment getCoreAssignment() { return coreAssignment; }
    public ISpectrumAssignment getSpectrumAssignment() { return spectrumAssignment; }
    public void setCoreAssignment(ICoreAssignment coreAssignment) { this.coreAssignment = coreAssignment; }
    public void setSpectrumAssignment(ISpectrumAssignment spectrumAssignment) { this.spectrumAssignment = spectrumAssignment; }
    /** Sets the modulation policy; {@code null} keeps the default (distance-adaptive). */
    public void setModulationSelection(IModulationSelection modulationSelection) {
        if (modulationSelection != null) this.modulationSelection = modulationSelection;
    }
    public void setRegeneratorAssignment(com.snets2.rmsca.regenerator.IRegeneratorAssignment regeneratorAssignment) { this.regeneratorAssignment = regeneratorAssignment; }

    @Override
    public AllocationResult allocate(ControlPlane cp, Node source, Node destination, double bitRate) {
        if (routing == null || coreAssignment == null || spectrumAssignment == null) {
            throw new IllegalStateException("StandardIntegratedRMSCA sub-algorithms not properly initialized.");
        }

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

        // Blocking cause tracked across all attempts (the last specific failure wins)
        BlockingCause currentCause = BlockingCause.OTHER;
        Integer currentCoreId = null;
        boolean foundPathAndMod = false;
        boolean foundFreeSlots = false;
        Integer lastAttemptedCore = null;

        int passes = regeneratorAssignment == null ? 1 : 2;
        for (int pass = 0; pass < passes; pass++) {
            boolean withRegenerators = pass == 1;

            for (Path path : candidatePaths) {
                // 3. Modulation loop: candidate formats and their order come from the configured policy
                for (ModulationFormat mod : modulationSelection.candidateFormats(cp, path, bitRate)) {
                    boolean reachViolated = path.getLength() > mod.maxReach();
                    if (reachViolated && !withRegenerators) continue;

                    foundPathAndMod = true;
                    int numSlots = SlotCalculator.requiredSlots(bitRate, mod, cp);

                    // 4. Core loop, in the order given by the core assignment strategy
                    for (Integer coreId : coreAssignment.selectCores(cp, path)) {
                        lastAttemptedCore = coreId;

                        // 5. Spectrum assignment
                        SpectrumInterval slots = spectrumAssignment.findSlots(cp, path, coreId, numSlots);
                        if (slots == null) continue;
                        foundFreeSlots = true;

                        // 6. Regenerator placement (pass 2 only)
                        List<Node> regens = List.of();
                        if (withRegenerators) {
                            regens = regeneratorAssignment.assignRegenerators(
                                    cp, path, coreId, mod, slots.start(), slots.end(), bitRate);
                            // null: no feasible placement; empty: transparent, already evaluated in pass 1
                            if (regens == null || regens.isEmpty()) continue;
                        }

                        // 7. QoT validation (new circuit and active circuits)
                        BlockingCause failure = evaluate(cp, path, regens, coreId, slots, mod, bitRate);
                        if (failure != null) {
                            currentCause = failure;
                            currentCoreId = coreId;
                            continue;
                        }

                        // 8. Success
                        return new AllocationResult(
                            source, destination, path.links(), getCoreIndicesList(path.links().size(), coreId),
                            slots.start(), slots.end(), mod, bitRate, regens
                        );
                    }
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

    /**
     * Validates the physical layer of a candidate allocation.
     *
     * @return {@code null} if the candidate is feasible, otherwise the blocking cause:
     *         {@link BlockingCause#QOT_NEW} / {@link BlockingCause#CROSSTALK} for the new circuit and
     *         {@link BlockingCause#QOT_OTHERS} / {@link BlockingCause#XT_OTHERS} for active circuits.
     */
    private BlockingCause evaluate(ControlPlane cp, Path path, List<Node> regens, int coreId,
                                   SpectrumInterval slots, ModulationFormat mod, double bitRate) {
        PhysicalLayerConfig physConfig = cp.getPhysicalLayerConfig();
        if (physConfig == null || !physConfig.activeQoT()) return null;

        // a. SNR of the new circuit (min over the transparent segments)
        double snr = PhysicalLayerModel.predictSNR(cp, path, regens, coreId, slots.start(), slots.end(), mod, bitRate);
        if (snr < mod.getSnrThresholdLinear()) {
            if (physConfig.activeXT()) {
                double snrNoXt = PhysicalLayerModel.predictSnrWithoutXt(cp, path, regens, coreId, slots.start(), slots.end(), mod, bitRate);
                if (snrNoXt >= mod.getSnrThresholdLinear()) return BlockingCause.CROSSTALK;
            }
            return BlockingCause.QOT_NEW;
        }

        // b. Crosstalk of the new circuit against the threshold of its modulation format
        if (physConfig.activeXT()) {
            double xt = PhysicalLayerModel.predictXtRatio(cp, path, regens, coreId, slots.start(), slots.end());
            if (xt > mod.getCrosstalkThresholdLinear()) return BlockingCause.CROSSTALK;
        }

        // c. SNR and crosstalk of the active circuits with the candidate's interference applied
        if (!physConfig.activeQoTForOther()) return null;
        applyTemporaryCircuit(cp, path, coreId, slots.start(), slots.end(), mod, bitRate, regens, true);
        try {
            for (Circuit active : cp.getActiveCircuitsView()) {
                Path activePath = new Path(active.getPath());
                int activeCore = active.getCoreIndices().get(0);
                ModulationFormat activeMod = active.getModulation();

                double activeSnr = PhysicalLayerModel.predictSNR(cp, activePath, active.getRegeneratorNodes(),
                        activeCore, active.getStartSlot(), active.getEndSlot(), activeMod, active.getBitRate());
                if (activeSnr < activeMod.getSnrThresholdLinear()) {
                    if (physConfig.activeXTForOther()) {
                        double activeSnrNoXt = PhysicalLayerModel.predictSnrWithoutXt(cp, activePath, active.getRegeneratorNodes(),
                                activeCore, active.getStartSlot(), active.getEndSlot(), activeMod, active.getBitRate());
                        if (activeSnrNoXt >= activeMod.getSnrThresholdLinear()) return BlockingCause.XT_OTHERS;
                    }
                    return BlockingCause.QOT_OTHERS;
                }
                if (physConfig.activeXTForOther()) {
                    double activeXt = PhysicalLayerModel.predictXtRatio(cp, activePath, active.getRegeneratorNodes(),
                            activeCore, active.getStartSlot(), active.getEndSlot());
                    if (activeXt > activeMod.getCrosstalkThresholdLinear()) return BlockingCause.XT_OTHERS;
                }
            }
        } finally {
            applyTemporaryCircuit(cp, path, coreId, slots.start(), slots.end(), mod, bitRate, regens, false);
        }
        return null;
    }

    /**
     * Temporarily applies (or removes) the physical footprint of the candidate circuit (NLI, crosstalk and
     * amplifier load) so the QoT of the already established circuits can be re-evaluated (QoTO).
     */
    private void applyTemporaryCircuit(ControlPlane cp, Path path, int coreId, int startSlot, int endSlot, ModulationFormat mod, double bitRate, List<Node> regens, boolean add) {
        if (cp.getPhysicalLayerConfig() == null) return;

        Circuit tempCircuit = new Circuit("temp", cp.getNode(path.links().get(0).getSourceId()), 
                                          cp.getNode(path.links().get(path.links().size()-1).getDestinationId()), 
                                          path.links(), getCoreIndicesList(path.links().size(), coreId), 
                                          startSlot, endSlot, mod, bitRate, regens);
        cp.applyPhysicalContribution(tempCircuit, add);
    }

    private List<Integer> getCoreIndicesList(int size, int coreId) {
        List<Integer> list = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            list.add(coreId);
        }
        return list;
    }
}
