package com.snets2.rmsca;

import com.snets2.config.PhysicalLayerConfig;
import com.snets2.metrics.PhysicalLayerModel;
import com.snets2.metrics.BlockingCause;
import com.snets2.model.*;
import com.snets2.rmsca.core.CoreSpectrumCandidate;
import com.snets2.rmsca.core.ICoreAndSpectrumAssignment;
import com.snets2.rmsca.core.ICoreAssignment;
import com.snets2.rmsca.core.SequentialCoreAndSpectrumAssignment;
import com.snets2.rmsca.modulation.DistanceAdaptiveModulationSelection;
import com.snets2.rmsca.modulation.IModulationSelection;
import com.snets2.rmsca.modulation.SlotCalculator;
import com.snets2.rmsca.routing.IRouting;
import com.snets2.rmsca.routing.Path;
import com.snets2.rmsca.spectrum.ISpectrumAssignment;
import com.snets2.rmsca.spectrum.SpectrumInterval;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * A standard sequential implementation of RMSCA with physical layer awareness.
 *
 * <p>Execution sequence: Tx/Rx check -> Routing -> for each pass (transparent, then regenerated) ->
 * Path loop -> Modulation loop (order given by {@link IModulationSelection}) -> (core, interval) candidates of the
 * {@link ICoreAndSpectrumAssignment} -> [Regenerator placement] -> QoT validation. Without a joint core and spectrum
 * algorithm the candidates are one per core, in the order of the {@link ICoreAssignment}, with the interval of the
 * {@link ISpectrumAssignment} ({@link SequentialCoreAndSpectrumAssignment}).</p>
 *
 * <p><b>Pass 1 (transparent)</b> only tries formats whose {@code maxReach} covers the path, without
 * regenerators. <b>Pass 2</b> runs only if pass 1 failed and a regenerator assignment is configured:
 * it retries every format with regenerators placed by the assignment. Hence a transparent solution
 * with a less efficient format is always preferred to a regenerated one. When the modulation policy does
 * not enforce the reach ({@link IModulationSelection#enforcesReach}, e.g. {@code qot-adaptive}), no format is
 * discarded by {@code maxReach} in either pass and the regenerators are placed by the QoT of the segments only;
 * the format is the same on all the segments of the path.</p>
 *
 * <p>Precedence: path, then format, then core. For a given path, the most preferred format is tried on every
 * (core, interval) candidate before the next format; the first candidate that passes the validation is accepted.</p>
 *
 * <p>With a modulation policy that prefers a margin ({@link IModulationSelection#snrMarginDb}, e.g. {@code qot-margin}),
 * the first candidate of a path whose new circuit keeps the margin is accepted; if none keeps it, the first feasible
 * candidate of the path is accepted at the end of the path. Without margins the flow is unchanged.</p>
 *
 * <p>Every candidate is validated by the same {@link #evaluate} step: SNR of the new circuit, its
 * crosstalk against the modulation threshold, and the SNR/crosstalk of the circuits already active.</p>
 */
public class StandardIntegratedRMSCA implements IRMSCA {

    private IRouting routing;
    private ICoreAssignment coreAssignment;
    private ISpectrumAssignment spectrumAssignment;
    private ICoreAndSpectrumAssignment coreAndSpectrumAssignment; // joint algorithm; null = sequential core + spectrum
    private com.snets2.rmsca.regenerator.IRegeneratorAssignment regeneratorAssignment;
    private IModulationSelection modulationSelection = new DistanceAdaptiveModulationSelection();

    public void setRouting(IRouting routing) { this.routing = routing; }
    public ICoreAssignment getCoreAssignment() { return coreAssignment; }
    public ISpectrumAssignment getSpectrumAssignment() { return spectrumAssignment; }
    public ICoreAndSpectrumAssignment getCoreAndSpectrumAssignment() { return coreAndSpectrumAssignment; }
    public void setCoreAssignment(ICoreAssignment coreAssignment) { this.coreAssignment = coreAssignment; }
    public void setSpectrumAssignment(ISpectrumAssignment spectrumAssignment) { this.spectrumAssignment = spectrumAssignment; }
    /**
     * Sets a joint core and spectrum assignment (e.g. ABNE), which replaces the core and spectrum assignments;
     * {@code null} restores the sequential combination of {@link #setCoreAssignment} and {@link #setSpectrumAssignment}.
     */
    public void setCoreAndSpectrumAssignment(ICoreAndSpectrumAssignment coreAndSpectrumAssignment) {
        this.coreAndSpectrumAssignment = coreAndSpectrumAssignment;
    }
    /** Sets the modulation policy; {@code null} keeps the default (distance-adaptive). */
    public void setModulationSelection(IModulationSelection modulationSelection) {
        if (modulationSelection != null) this.modulationSelection = modulationSelection;
    }
    public void setRegeneratorAssignment(com.snets2.rmsca.regenerator.IRegeneratorAssignment regeneratorAssignment) { this.regeneratorAssignment = regeneratorAssignment; }

    /** The configured sub-algorithms (routing, modulation, core, spectrum and, if any, regenerator assignment). */
    public List<Object> components() {
        List<Object> list = new ArrayList<>();
        Object[] all = coreAndSpectrumAssignment != null
                ? new Object[] {routing, modulationSelection, coreAndSpectrumAssignment, regeneratorAssignment}
                : new Object[] {routing, modulationSelection, coreAssignment, spectrumAssignment, regeneratorAssignment};
        for (Object o : all) {
            if (o != null) list.add(o);
        }
        return list;
    }

    @Override
    public AllocationResult allocate(ControlPlane cp, Node source, Node destination, double bitRate) {
        if (routing == null || (coreAndSpectrumAssignment == null && (coreAssignment == null || spectrumAssignment == null))) {
            throw new IllegalStateException("StandardIntegratedRMSCA sub-algorithms not properly initialized.");
        }
        ICoreAndSpectrumAssignment coreAndSpectrum = coreAndSpectrumAssignment != null ? coreAndSpectrumAssignment
                : new SequentialCoreAndSpectrumAssignment(coreAssignment, spectrumAssignment);

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

        boolean enforceReach = modulationSelection.enforcesReach(cp);
        // Preferred margins of the new circuit (qot-margin); factors of 1 = no margin
        double snrMarginFactor = Math.pow(10, modulationSelection.snrMarginDb(cp) / 10);
        double xtMarginFactor = Math.pow(10, modulationSelection.xtMarginDb(cp) / 10);
        boolean byCost = selectsByCost();
        int passes = regeneratorAssignment == null ? 1 : 2;
        for (int pass = 0; pass < passes; pass++) {
            boolean withRegenerators = pass == 1;
            // Cost-based selection (selectsByCost): best candidate of the pass keeping the margin, and best overall
            AllocationResult bestKept = null, bestAny = null;
            double bestKeptCost = Double.POSITIVE_INFINITY, bestAnyCost = Double.POSITIVE_INFINITY;

            for (Path path : candidatePaths) {
                // First feasible candidate of this path that does not keep the margin (used if none keeps it)
                AllocationResult withoutMargin = null;
                // 3. Modulation loop: candidate formats and their order come from the configured policy
                for (ModulationFormat mod : modulationSelection.candidateFormats(cp, path, bitRate)) {
                    boolean reachViolated = enforceReach && path.getLength() > mod.maxReach();
                    if (reachViolated && !withRegenerators) continue;

                    foundPathAndMod = true;
                    int numSlots = SlotCalculator.requiredSlots(bitRate, mod, cp);

                    // 4-5. Core and spectrum candidates, in the order of the (joint) core and spectrum assignment
                    for (CoreSpectrumCandidate candidateSlots : coreAndSpectrum.candidates(cp, path, numSlots)) {
                        int coreId = candidateSlots.core();
                        lastAttemptedCore = coreId;
                        SpectrumInterval slots = candidateSlots.slots();
                        if (slots == null) continue;
                        foundFreeSlots = true;

                        // 6. Regenerator placement (pass 2 only)
                        List<Node> regens = List.of();
                        if (withRegenerators) {
                            regens = regeneratorAssignment.assignRegenerators(
                                    cp, path, coreId, mod, slots.start(), slots.end(), bitRate, enforceReach);
                            // null: no feasible placement; empty: transparent, already evaluated in pass 1
                            if (regens == null || regens.isEmpty()) continue;
                        }

                        // 7. QoT validation (new circuit and active circuits)
                        Verdict verdict = evaluate(cp, path, regens, coreId, slots, mod, bitRate,
                                snrMarginFactor, xtMarginFactor, byCost || withoutMargin == null);
                        if (verdict.failure() != null) {
                            currentCause = verdict.failure();
                            currentCoreId = coreId;
                            continue;
                        }
                        AllocationResult candidate = new AllocationResult(
                            source, destination, path.links(), getCoreIndicesList(path.links().size(), coreId),
                            slots.start(), slots.end(), mod, bitRate, regens
                        );

                        if (byCost) { // keep the cheapest feasible candidate (ties: the first one) and go on
                            double cost = candidateCost(cp, path, regens, coreId, slots, mod);
                            if (verdict.marginKept() && cost < bestKeptCost) { bestKept = candidate; bestKeptCost = cost; }
                            if (cost < bestAnyCost) { bestAny = candidate; bestAnyCost = cost; }
                            continue;
                        }

                        // 8. Success (with the margin, if any)
                        if (verdict.marginKept()) return candidate;
                        if (withoutMargin == null) withoutMargin = candidate;
                    }
                }
                // No candidate of this path keeps the margin: the first feasible one
                if (withoutMargin != null) return withoutMargin;
            }
            if (bestKept != null) return bestKept;
            if (bestAny != null) return bestAny;
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
     * Whether the RMSCA chooses, in each pass, the feasible candidate of lowest {@link #candidateCost} over all paths,
     * formats and (core, interval) candidates, instead of accepting the first feasible one. False here; integrated
     * algorithms such as {@code kspxt} override it. With a preferred margin, the cheapest candidate keeping the margin
     * wins over any candidate that does not.
     */
    protected boolean selectsByCost() {
        return false;
    }

    /** Cost of a feasible candidate when {@link #selectsByCost()}; lower is better, ties keep the first candidate. */
    protected double candidateCost(ControlPlane cp, Path path, List<Node> regens, int coreId, SpectrumInterval slots,
                                   ModulationFormat mod) {
        return 0;
    }

    /**
     * Outcome of the validation of a candidate.
     *
     * @param failure    {@code null} if the candidate is feasible, otherwise the blocking cause
     * @param marginKept whether the new circuit keeps the preferred margins (always true without margins)
     */
    private record Verdict(BlockingCause failure, boolean marginKept) {
        static final Verdict FEASIBLE = new Verdict(null, true);
        static final Verdict FEASIBLE_WITHOUT_MARGIN = new Verdict(null, false);
    }

    /**
     * Validates the physical layer of a candidate allocation.
     *
     * <p>{@code snrMarginFactor} and {@code xtMarginFactor} (linear, {@code >= 1}) are the preferred margins of the new
     * circuit ({@link IModulationSelection#snrMarginDb}). A candidate that does not keep them is still feasible; if
     * {@code checkOthersWithoutMargin} is false (a feasible candidate without margin is already known on this path),
     * such a candidate cannot be chosen and the costly check of the active circuits is skipped.</p>
     *
     * @return {@link Verdict#failure()} {@code null} if the candidate is feasible, otherwise the blocking cause:
     *         {@link BlockingCause#QOT_NEW} / {@link BlockingCause#CROSSTALK} for the new circuit and
     *         {@link BlockingCause#QOT_OTHERS} / {@link BlockingCause#XT_OTHERS} for active circuits.
     */
    private Verdict evaluate(ControlPlane cp, Path path, List<Node> regens, int coreId, SpectrumInterval slots,
                             ModulationFormat mod, double bitRate, double snrMarginFactor, double xtMarginFactor,
                             boolean checkOthersWithoutMargin) {
        PhysicalLayerConfig physConfig = cp.getPhysicalLayerConfig();
        if (physConfig == null || !physConfig.activeQoT()) return Verdict.FEASIBLE;

        // a. SNR of the new circuit (min over the transparent segments)
        double snr = PhysicalLayerModel.predictSNR(cp, path, regens, coreId, slots.start(), slots.end(), mod, bitRate);
        if (snr < mod.getSnrThresholdLinear()) {
            if (physConfig.activeXT()) {
                double snrNoXt = PhysicalLayerModel.predictSnrWithoutXt(cp, path, regens, coreId, slots.start(), slots.end(), mod, bitRate);
                if (snrNoXt >= mod.getSnrThresholdLinear()) return new Verdict(BlockingCause.CROSSTALK, false);
            }
            return new Verdict(BlockingCause.QOT_NEW, false);
        }
        boolean marginKept = snrMarginFactor <= 1 || snr >= mod.getSnrThresholdLinear() * snrMarginFactor;

        // b. Crosstalk of the new circuit against the threshold of its modulation format
        if (physConfig.activeXT()) {
            double xt = PhysicalLayerModel.predictXtRatio(cp, path, regens, coreId, slots.start(), slots.end());
            if (xt > mod.getCrosstalkThresholdLinear()) return new Verdict(BlockingCause.CROSSTALK, false);
            marginKept &= xtMarginFactor <= 1 || xt <= mod.getCrosstalkThresholdLinear() / xtMarginFactor;
        }
        if (!marginKept && !checkOthersWithoutMargin) return Verdict.FEASIBLE_WITHOUT_MARGIN; // not selectable anyway

        // c. SNR and crosstalk of the active circuits with the candidate's interference applied
        Verdict feasible = marginKept ? Verdict.FEASIBLE : Verdict.FEASIBLE_WITHOUT_MARGIN;
        if (!physConfig.activeQoTForOther()) return feasible;
        applyTemporaryCircuit(cp, path, coreId, slots.start(), slots.end(), mod, bitRate, regens, true);
        try {
            Set<Link> candidateLinks = Collections.newSetFromMap(new IdentityHashMap<>());
            candidateLinks.addAll(path.links());
            for (Circuit active : cp.getActiveCircuitsView()) {
                if (!isAffectedByCandidate(active, candidateLinks, coreId)) continue;
                Path activePath = new Path(active.getPath());
                int activeCore = active.getCoreIndices().get(0);
                ModulationFormat activeMod = active.getModulation();

                double activeSnr = PhysicalLayerModel.predictSNR(cp, activePath, active.getRegeneratorNodes(),
                        activeCore, active.getStartSlot(), active.getEndSlot(), activeMod, active.getBitRate());
                if (activeSnr < activeMod.getSnrThresholdLinear()) {
                    if (physConfig.activeXTForOther()) {
                        double activeSnrNoXt = PhysicalLayerModel.predictSnrWithoutXt(cp, activePath, active.getRegeneratorNodes(),
                                activeCore, active.getStartSlot(), active.getEndSlot(), activeMod, active.getBitRate());
                        if (activeSnrNoXt >= activeMod.getSnrThresholdLinear()) return new Verdict(BlockingCause.XT_OTHERS, false);
                    }
                    return new Verdict(BlockingCause.QOT_OTHERS, false);
                }
                if (physConfig.activeXTForOther()) {
                    double activeXt = PhysicalLayerModel.predictXtRatio(cp, activePath, active.getRegeneratorNodes(),
                            activeCore, active.getStartSlot(), active.getEndSlot());
                    if (activeXt > activeMod.getCrosstalkThresholdLinear()) return new Verdict(BlockingCause.XT_OTHERS, false);
                }
            }
        } finally {
            applyTemporaryCircuit(cp, path, coreId, slots.start(), slots.end(), mod, bitRate, regens, false);
        }
        return feasible;
    }

    /**
     * Whether an active circuit can be affected by a candidate on {@code candidateLinks}/{@code coreId}: it shares at
     * least one link with the candidate, in the same core (NLI and amplifier load) or in a core adjacent to it
     * (crosstalk). This mirrors the footprint written by {@link ControlPlane#applyPhysicalContribution}; the noise of
     * any other circuit is left untouched by the candidate, so skipping it does not change the QoTO decision as long as
     * the established circuits already satisfy their thresholds (which this very check maintains).
     */
    private static boolean isAffectedByCandidate(Circuit active, Set<Link> candidateLinks, int coreId) {
        List<Link> links = active.getPath();
        for (int i = 0; i < links.size(); i++) {
            Link link = links.get(i);
            if (!candidateLinks.contains(link)) continue;
            int activeCore = active.getCoreIndices().get(i);
            if (activeCore == coreId) return true;
            Core candidateCore = link.getCore(coreId);
            if (candidateCore != null && candidateCore.getAdjacentCores().contains(activeCore)) return true;
        }
        return false;
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
