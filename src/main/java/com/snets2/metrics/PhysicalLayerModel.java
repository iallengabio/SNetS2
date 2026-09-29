package com.snets2.metrics;

import com.snets2.config.PhysicalLayerConfig;
import com.snets2.model.*;
import com.snets2.rmsca.routing.Path;
import java.util.List;

/**
 * Mathematical engine for physical layer impairments (OSNR, NLI, ASE, Crosstalk).
 * 
 * <p>This class implements the Incremental State Caching logic, where noise is 
 * calculated during connection establishment and stored in the Link/Core caches.</p>
 */
public class PhysicalLayerModel {

    /**
     * Calculates the static ASE noise density (W/Hz) for a single link.
     * Considers Booster, Line, and Pre-amplifiers.
     */
    public static double calculateLinkAse(Link link, PhysicalLayerConfig config, double slotBandwidth) {
        if (!config.activeASE()) return 0.0;

        double h = config.constantOfPlanck();
        double f = config.amplificationFrequency();
        double nfLinear = Math.pow(10, config.noiseFigureOfOpticalAmplifier() / 10.0);
        
        // Simplified ASE calculation per amplifier: P_ase = NF * h * f * (G - 1) * B
        // Here we calculate density: I_ase = NF * h * f * (G - 1)
        // We assume Gain compensates for loss (G = SpanLoss)
        double spanLossLinear = Math.pow(10, (config.fiberLoss() * config.spanLength()) / 10.0);
        double gainLinear = spanLossLinear; 
        
        double aseDensityPerAmp = nfLinear * h * f * (gainLinear - 1.0);
        
        // Total ASE = Booster + (N_line * LineAmp) + PreAmp
        // N_line = floor(L / span)
        int nLine = (int) Math.floor(link.getLength() / config.spanLength());
        
        // In a simplified model, let's say Booster and PreAmp also have similar NF/Gain
        return (2 + nLine) * aseDensityPerAmp;
    }

    /**
     * Calculates the Crosstalk noise density (W/Hz) contribution of a circuit to an adjacent core.
     */
    public static double calculateXtContribution(Link link, PhysicalLayerConfig config, Circuit circuit) {
        if (!config.activeXT()) return 0.0;

        // Lobato Model: P_xt = P_j * h * L
        // Noise Density I_xt = P_xt / Bandwidth
        double pLinear = Math.pow(10, config.power() / 10.0) * 1E-3; // Default power in Watts
        
        // h (power-coupling coefficient)
        double hFiber = (2.0 * Math.pow(config.couplingCoefficient(), 2) * config.bendingRadius()) / 
                        (config.propagationConstant() * config.corePitch());
        
        double pXt = pLinear * hFiber * (link.getLength() * 1000.0); // Length in meters
        
        double bandwidth = signalBandwidth(circuit.getStartSlot(), circuit.getEndSlot(), config.guardBand(), config.bvtSpectralWidth());

        return pXt / bandwidth;
    }

    // ------------------------------------------------------------------------------------------
    // Non-linear interference (NLI): incoherent GN model. For a victim channel i (total dual-
    // polarization PSD G_i, bandwidth B_i), per span:
    //
    //   G_NLI,i = mu * G_i * [ G_i^2 * asinh(rho * B_i^2)                                  (SCI)
    //                          + sum_{j != i} G_j^2 * ln((|df_ij| + B_j/2) / (|df_ij| - B_j/2)) ] (XCI)
    //
    //   mu  = (8/27) gamma^2 Leff^2 / (pi |beta2| Leff,a),   rho = (pi^2 / 2) |beta2| Leff,a
    //   Leff = (1 - exp(-alpha Ls)) / alpha,  Leff,a = 1 / alpha   (alpha: power attenuation)
    //
    // The SCI term is Poggiolini's GN closed form; the XCI term (Johannisson & Karlsson form) is its
    // large-dispersion limit, consistent with it: for equal, contiguous channels SCI + sum XCI equals
    // the SCI of the whole occupied band. Spans add incoherently (N_spans = ceil(L / spanLength)).
    // The XCI sum is kept in the
    // per-slot cache of each core (see generateNliMask); the SCI term and the victim factor G_i
    // are applied at prediction time, so a channel never interferes with itself through the cache.
    // ------------------------------------------------------------------------------------------

    /** Attenuation coefficient alpha (1/m) from fiberLoss (dB/km). */
    static double alpha(PhysicalLayerConfig config) {
        return config.fiberLoss() / (10.0 * Math.log10(Math.E) * 1000.0);
    }

    /** |beta2| (s^2/m) from the dispersion parameter D (s/m^2): |beta2| = D * lambda^2 / (2 pi c). */
    static double beta2(PhysicalLayerConfig config) {
        double c = 299792458.0;
        double lambda = c / config.centerFrequency();
        return Math.abs(config.fiberDispersion() * lambda * lambda / (2.0 * Math.PI * c));
    }

    /** Per-span GN coefficient mu = (8/27) gamma^2 Leff^2 / (pi |beta2| Leff,a), gamma in 1/(W m). */
    static double nliMu(PhysicalLayerConfig config) {
        double gamma = config.fiberNonlinearity();
        double a = alpha(config);
        double leff = (1.0 - Math.exp(-a * config.spanLength() * 1000.0)) / a;
        double leffA = 1.0 / a;
        return (8.0 / 27.0) * gamma * gamma * leff * leff / (Math.PI * beta2(config) * leffA);
    }

    /** rho = (pi^2 / 2) |beta2| Leff,a (s^2, multiplies a bandwidth squared). */
    static double nliRho(PhysicalLayerConfig config) {
        return Math.PI * Math.PI / 2.0 * beta2(config) / alpha(config);
    }

    /** Number of fiber spans of a link (at least one). */
    static int numberOfSpans(Link link, PhysicalLayerConfig config) {
        return Math.max(1, (int) Math.ceil(link.getLength() / config.spanLength()));
    }

    /** Launch power per channel (W) from {@code power} (dBm). */
    static double launchPowerWatts(PhysicalLayerConfig config) {
        return Math.pow(10, config.power() / 10.0) * 1E-3;
    }

    /**
     * Self-channel interference PSD (W/Hz) of a channel of bandwidth {@code bandwidth} (Hz) on a link:
     * {@code N_spans * mu * G^3 * asinh(rho * B^2)}.
     */
    public static double selfChannelInterference(Link link, PhysicalLayerConfig config, double bandwidth) {
        if (!config.activeNLI()) return 0.0;
        double g = launchPowerWatts(config) / bandwidth;
        return numberOfSpans(link, config) * nliMu(config) * g * g * g
                * asinh(nliRho(config) * bandwidth * bandwidth);
    }

    /**
     * Cross-channel NLI contribution of {@code circuit} (the interferer j) to every slot of its core.
     *
     * <p>Entry {@code s} is {@code N_spans * mu * G_j^2 * ln((|df| + B_j/2) / (|df| - B_j/2))}, with
     * {@code df} the distance between the centre of slot {@code s} and the centre of the interferer.
     * Slots occupied by the interferer itself are zero. The cache is multiplied by the PSD {@code G_i}
     * of the victim at prediction time, which yields W/Hz.</p>
     */
    public static double[] generateNliMask(Link link, PhysicalLayerConfig config, Circuit circuit, int totalSlots) {
        double[] mask = new double[totalSlots];
        if (!config.activeNLI()) return mask;

        double slotWidth = config.bvtSpectralWidth();
        double bandwidth = signalBandwidth(circuit.getStartSlot(), circuit.getEndSlot(), config.guardBand(), slotWidth);
        double g = launchPowerWatts(config) / bandwidth;
        double factor = numberOfSpans(link, config) * nliMu(config) * g * g;
        double center = (circuit.getStartSlot() + circuit.getEndSlot() + 1) / 2.0; // in slot units

        for (int s = 0; s < totalSlots; s++) {
            if (s >= circuit.getStartSlot() && s <= circuit.getEndSlot()) continue;
            double deltaF = Math.abs(s + 0.5 - center) * slotWidth;
            mask[s] = factor * Math.log((deltaF + bandwidth / 2.0) / (deltaF - bandwidth / 2.0));
        }
        return mask;
    }

    /**
     * Bandwidth (Hz) actually occupied by the signal of an allocation {@code [startSlot, endSlot]}: the
     * allocated range includes {@code guardBand} guard slots, which carry no signal power. At least one
     * slot is always considered. The PSD of the channel is {@code P / signalBandwidth}.
     */
    public static double signalBandwidth(int startSlot, int endSlot, int guardBand, double slotWidth) {
        int allocated = endSlot - startSlot + 1;
        return Math.max(1, allocated - Math.max(0, guardBand)) * slotWidth;
    }

    private static double asinh(double x) {
        return Math.log(x + Math.sqrt(x * x + 1.0));
    }

    /**
     * Calculates the total number of overlapping slots with adjacent cores across the entire path.
     */
    public static int calculateTotalOverlaps(Path path, int coreId, int startSlot, int endSlot) {
        int totalOverlaps = 0;
        for (Link link : path.links()) {
            Core core = link.getCore(coreId);
            if (core == null) continue;
            
            for (int adjId : core.getAdjacentCores()) {
                Core adjCore = link.getCore(adjId);
                if (adjCore != null) {
                    for (int s = startSlot; s <= endSlot; s++) {
                        if (adjCore.getSpectrum().isOccupied(s)) {
                            totalOverlaps++;
                        }
                    }
                }
            }
        }
        return totalOverlaps;
    }

    /**
     * Inter-core crosstalk ratio (linear, dimensionless) of a proposed allocation on one transparent
     * segment: {@code XT = sum_links avg(I_XT) / I_ch}, i.e. the crosstalk power coupled into the victim
     * divided by its signal power. For one fully-overlapping neighbour of the same bandwidth on a link of
     * length {@code L} this equals {@code h L}.
     */
    public static double predictXtRatio(ControlPlane cp, Path path, int coreId, int startSlot, int endSlot) {
        PhysicalLayerConfig config = cp.getPhysicalLayerConfig();
        double pLinear = config != null ? launchPowerWatts(config) : 1E-4;
        double iCh = pLinear / signalBandwidth(startSlot, endSlot, cp.getGuardBand(), cp.getSlotBandwidth());
        double totalXtDensity = 0;
        for (Link link : path.links()) {
            totalXtDensity += link.getCore(coreId).getAverageXtNoise(startSlot, endSlot);
        }
        return totalXtDensity / iCh;
    }

    /**
     * Predicts the SNR (linear, in the signal bandwidth) of a proposed allocation on one transparent
     * segment: {@code I_ch / sum_links (I_ASE + I_NLI + I_XT)}, with {@code I_ch = P / B}.
     */
    public static double predictSNR(ControlPlane cp, Path path, int coreId, int startSlot, int endSlot, ModulationFormat mod, double bitRate) {
        return predictSegmentSnr(cp, path, coreId, startSlot, endSlot, true);
    }

    /**
     * Predicts the SNR (linear) for a proposed allocation excluding inter-core crosstalk.
     */
    public static double predictSnrWithoutXt(ControlPlane cp, Path path, int coreId, int startSlot, int endSlot, ModulationFormat mod, double bitRate) {
        return predictSegmentSnr(cp, path, coreId, startSlot, endSlot, false);
    }

    private static double predictSegmentSnr(ControlPlane cp, Path path, int coreId, int startSlot, int endSlot, boolean includeXt) {
        PhysicalLayerConfig config = cp.getPhysicalLayerConfig();
        double pLinear = config != null ? launchPowerWatts(config) : 1E-4; // 1E-4 W: legacy fallback
        double bandwidth = signalBandwidth(startSlot, endSlot, cp.getGuardBand(), cp.getSlotBandwidth());
        double iCh = pLinear / bandwidth;

        double totalNoiseDensity = 0;
        for (Link link : path.links()) {
            Core core = link.getCore(coreId);
            totalNoiseDensity += link.getStaticAseNoise();
            if (config != null && config.activeNLI()) {
                totalNoiseDensity += iCh * core.getAverageNliNoise(startSlot, endSlot)
                        + selfChannelInterference(link, config, bandwidth);
            }
            if (includeXt) {
                totalNoiseDensity += core.getAverageXtNoise(startSlot, endSlot);
            }
        }
        return iCh / Math.max(1E-30, totalNoiseDensity);
    }

    public static double predictSNR(ControlPlane cp, Path path, List<Node> regenerators, int coreId, int startSlot, int endSlot, ModulationFormat mod, double bitRate) {
        List<Path> segments = getPathSegments(path, regenerators);
        double minSnr = Double.MAX_VALUE;
        for (Path segment : segments) {
            double snr = predictSNR(cp, segment, coreId, startSlot, endSlot, mod, bitRate);
            if (snr < minSnr) {
                minSnr = snr;
            }
        }
        return minSnr;
    }

    public static double predictSnrWithoutXt(ControlPlane cp, Path path, List<Node> regenerators, int coreId, int startSlot, int endSlot, ModulationFormat mod, double bitRate) {
        List<Path> segments = getPathSegments(path, regenerators);
        double minSnr = Double.MAX_VALUE;
        for (Path segment : segments) {
            double snr = predictSnrWithoutXt(cp, segment, coreId, startSlot, endSlot, mod, bitRate);
            if (snr < minSnr) {
                minSnr = snr;
            }
        }
        return minSnr;
    }

    /**
     * Worst (largest) crosstalk ratio over the transparent segments delimited by regenerators, linear.
     */
    public static double predictXtRatio(ControlPlane cp, Path path, List<Node> regenerators, int coreId, int startSlot, int endSlot) {
        double worst = 0;
        for (Path segment : getPathSegments(path, regenerators)) {
            worst = Math.max(worst, predictXtRatio(cp, segment, coreId, startSlot, endSlot));
        }
        return worst;
    }

    /**
     * Worst crosstalk over the transparent segments, in dB ({@code 10 log10(XT ratio)}); comparable with the
     * {@code XT} threshold of the modulation formats. Allocations without any overlapping neighbour are
     * reported at the floor of -300 dB.
     */
    public static double predictXT(ControlPlane cp, Path path, List<Node> regenerators, int coreId, int startSlot, int endSlot) {
        return 10 * Math.log10(Math.max(1E-30, predictXtRatio(cp, path, regenerators, coreId, startSlot, endSlot)));
    }

    private static List<Path> getPathSegments(Path path, List<Node> regenerators) {
        List<Path> segments = new java.util.ArrayList<>();
        if (regenerators == null || regenerators.isEmpty()) {
            segments.add(path);
            return segments;
        }
        
        java.util.Set<String> regenIds = new java.util.HashSet<>();
        for (Node n : regenerators) {
            regenIds.add(n.getId());
        }
        
        List<Link> currentSegment = new java.util.ArrayList<>();
        for (Link link : path.links()) {
            if (regenIds.contains(link.getSourceId()) && !currentSegment.isEmpty()) {
                segments.add(new Path(currentSegment));
                currentSegment = new java.util.ArrayList<>();
            }
            currentSegment.add(link);
        }
        if (!currentSegment.isEmpty()) {
            segments.add(new Path(currentSegment));
        }
        
        return segments;
    }
}
