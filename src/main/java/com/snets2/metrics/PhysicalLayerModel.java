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

    // ------------------------------------------------------------------
    // Unit conversions
    // ------------------------------------------------------------------

    /** Converts a ratio in dB to linear scale. */
    public static double dbToLinear(double db) {
        return Math.pow(10.0, db / 10.0);
    }

    /** Converts a power in dBm to Watts. */
    public static double dbmToWatts(double dbm) {
        return dbToLinear(dbm) * 1E-3;
    }

    /** Converts a power in Watts to dBm. */
    public static double wattsToDbm(double watts) {
        return 10.0 * Math.log10(watts / 1E-3);
    }

    // ------------------------------------------------------------------
    // Launch power and power spectral density (fixedPowerSpectralDensity)
    // ------------------------------------------------------------------

    /**
     * Launch power (W) of a circuit whose signal occupies {@code bandwidth} Hz (see {@link #signalBandwidth}).
     *
     * <ul>
     *   <li>Variable PSD ({@code fixedPowerSpectralDensity = false}): every circuit is launched with
     *       {@code power}, so wider circuits have a lower PSD.</li>
     *   <li>Fixed PSD ({@code fixedPowerSpectralDensity = true}): {@code power} is the power in the
     *       reference bandwidth B_ref and every circuit keeps the PSD P_ref / B_ref, i.e.
     *       P_i = (P_ref / B_ref) B_i ("flat PSD" launch, as assumed by the GN-model closed forms in
     *       Poggiolini, JLT 2012, and Johannisson &amp; Agrell, JLT 2014).</li>
     * </ul>
     *
     * <p>Follows {@code PhysicalLayer.getCircuitLaunchPower} of SNetS v1.</p>
     */
    public static double circuitLaunchPower(PhysicalLayerConfig config, double bandwidth) {
        double referencePower = dbmToWatts(config.power());
        if (!config.fixedPowerSpectralDensity()) {
            return referencePower;
        }
        return referencePower / config.referenceBandwidthForPowerSpectralDensity() * bandwidth;
    }

    /** Launch power (W) of an allocated circuit. */
    public static double circuitLaunchPower(PhysicalLayerConfig config, Circuit circuit) {
        return circuitLaunchPower(config, circuitBandwidth(config, circuit));
    }

    /** Signal bandwidth (Hz) of an allocated circuit, guard band excluded. */
    static double circuitBandwidth(PhysicalLayerConfig config, Circuit circuit) {
        return signalBandwidth(circuit.getStartSlot(), circuit.getEndSlot(), config.guardBand(), config.bvtSpectralWidth());
    }

    // ------------------------------------------------------------------
    // ASE noise (amplifier chain of a link)
    // ------------------------------------------------------------------

    /**
     * Number of in-line amplifiers of a link: N_l = ceil(L / L_span - 1), never negative.
     * The last fiber segment (length L - N_l L_span, in (0, L_span]) is compensated by the pre-amplifier.
     */
    public static int numberOfLineAmplifiers(double linkLength, double spanLength) {
        return (int) Math.max(0, Math.ceil(linkLength / spanLength - 1.0));
    }

    /**
     * Nominal gains G_0 (dB) of the amplifier chain of a link, in propagation order: booster (3 x
     * {@code switchInsertionLoss}), N_l line amplifiers (alpha L_span) and pre-amplifier (alpha (L - N_l L_span)),
     * with N_l = {@link #numberOfLineAmplifiers}. This is the single rule for the amplifiers of a link: it is used by
     * {@link #calculateLinkAse} and by {@code TopologyMapper} to build {@code Link.getAmplifiers()}, which the
     * energy model counts, so that the ASE and the energy models always see the same N_l + 2 amplifiers.
     *
     * @param linkLength Link length L (km).
     * @return Array of N_l + 2 gains in dB.
     */
    public static double[] amplifierChainGainsDb(double linkLength, PhysicalLayerConfig config) {
        int nLine = numberOfLineAmplifiers(linkLength, config.spanLength());
        double lastSegment = linkLength - nLine * config.spanLength();

        double[] gains = new double[nLine + 2];
        gains[0] = 3.0 * config.switchInsertionLoss();
        for (int k = 1; k <= nLine; k++) {
            gains[k] = config.fiberLoss() * config.spanLength();
        }
        gains[nLine + 1] = config.fiberLoss() * lastSegment;
        return gains;
    }

    /**
     * ASE noise density (W/Hz, both polarizations) of the amplifier chain of one link, referred to the
     * nominal signal PSD so that it can be summed with the other noise terms in SNR = I / (I_ASE + I_NLI + I_XT).
     *
     * <p>Following SNetS v1 ({@code PhysicalLayer.computeSNRSegment}), each link has:</p>
     * <ul>
     *   <li>a <b>booster</b> that compensates the ROADM losses: G_0 = 3 x {@code switchInsertionLoss} dB
     *       (demultiplexer + switch + multiplexer);</li>
     *   <li>N_l <b>line amplifiers</b>, each compensating one span: G_0 = alpha L_span;</li>
     *   <li>a <b>pre-amplifier</b> compensating the last segment: G_0 = alpha (L - N_l L_span).</li>
     * </ul>
     *
     * <p>With fixed gain every amplifier restores the nominal power and the result is the plain sum of the ASE
     * of the amplifiers. With saturated gain the compressed gain G_k &lt; G_0,k also attenuates the signal, so
     * the chain is evaluated as a cascade (1/OSNR = sum_k 1/OSNR_k; Pereira et al., Photonic Netw. Commun.
     * 18, 2009): rho_k = rho_{k-1} G_k / G_0,k is the channel power at the output of amplifier k relative to
     * the nominal one, the input power of amplifier k is P_in,k = rho_{k-1} P_tot / G_0,k, and its ASE is
     * weighted by 1 / rho_k. Deviation from SNetS v1, which lowers the ASE under saturation but keeps the
     * signal at its nominal level (so the SNR would improve with the load). The power is assumed to be
     * re-equalized at every ROADM, hence rho restarts at 1 on each link.</p>
     *
     * @param totalLaunchPower Total power P_tot (W) launched into the link core, i.e. the sum of the launch powers
     *                         of the circuits sharing its amplifiers. Only used with saturated gain.
     */
    public static double calculateLinkAse(Link link, PhysicalLayerConfig config, double totalLaunchPower) {
        if (!config.activeASE()) return 0.0;

        double ase = 0.0;
        double rho = 1.0;
        for (double g0Db : amplifierChainGainsDb(link.getLength(), config)) {
            double g0 = dbToLinear(g0Db);
            double pIn = rho * totalLaunchPower / g0;
            double gain = amplifierGain(config, g0, pIn);
            rho *= gain / g0;
            ase += amplifierAse(config, gain, pIn) / rho;
        }
        return ase;
    }

    /**
     * Amplifier gain (linear).
     *
     * <ul>
     *   <li><b>Fixed gain</b> ({@code typeOfAmplifierGain = 0}): G = G_0.</li>
     *   <li><b>Saturated gain</b> ({@code typeOfAmplifierGain = 1}): G = G_0 / (1 + G_0 P_in / P_sat), clamped at
     *       G &ge; 1 (Pereira et al., "OSNR model to consider physical layer impairments in transparent optical
     *       networks", Photonic Netw. Commun. 18, 2009; {@code Amplifier.getGainSaturated} in SNetS v1). Unlike
     *       SNetS v1, the compression is applied continuously and not only once G_0 P_in exceeds P_sat, which
     *       would make the gain jump at that point.</li>
     * </ul>
     *
     * @param nominalGain Unsaturated gain G_0 (linear).
     * @param inputPower  Total input power P_in (W).
     */
    public static double amplifierGain(PhysicalLayerConfig config, double nominalGain, double inputPower) {
        if (config.typeOfAmplifierGain() != PhysicalLayerConfig.AMP_GAIN_SATURATED || inputPower <= 0) {
            return nominalGain;
        }
        double pSat = dbmToWatts(config.powerSaturationOfOpticalAmplifier());
        return Math.max(1.0, nominalGain / (1.0 + nominalGain * inputPower / pSat));
    }

    /**
     * ASE noise density (W/Hz, both polarizations) at the output of a single amplifier:
     * S_ase = F h nu (G - 1)  (Desurvire, "Erbium-Doped Fiber Amplifiers", 1994; Agrawal,
     * "Fiber-Optic Communication Systems", 4th ed., 2010).
     *
     * <p>The noise factor is F = NF with fixed gain. With saturated gain it follows the power-dependent model of
     * Pereira et al. (2009), as in {@code Amplifier.getFamp} of SNetS v1:
     * F = NF (1 + A_1 - A_1 / (1 + P_in / A_2)).</p>
     *
     * @param gain       Actual gain G (linear), see {@link #amplifierGain}.
     * @param inputPower Total input power P_in (W).
     */
    public static double amplifierAse(PhysicalLayerConfig config, double gain, double inputPower) {
        double noiseFactor = dbToLinear(config.noiseFigureOfOpticalAmplifier());
        double a2 = config.noiseFactorModelParameterA2();
        if (config.typeOfAmplifierGain() == PhysicalLayerConfig.AMP_GAIN_SATURATED && a2 > 0) {
            double a1 = config.noiseFactorModelParameterA1();
            noiseFactor *= 1.0 + a1 - a1 / (1.0 + inputPower / a2);
        }
        return noiseFactor * config.constantOfPlanck() * config.amplificationFrequency() * (gain - 1.0);
    }

    /**
     * ASE density of a link seen by a circuit on {@code core}. With fixed gain this is the static value cached in
     * the link; with saturated gain it is recomputed from the current core load.
     *
     * @param candidatePower Launch power (W) of the evaluated circuit, added to the load when it is not yet counted.
     */
    private static double linkAseForPrediction(Link link, Core core, PhysicalLayerConfig config,
                                               int startSlot, int endSlot, double candidatePower) {
        if (config.typeOfAmplifierGain() != PhysicalLayerConfig.AMP_GAIN_SATURATED) {
            return link.getStaticAseNoise();
        }
        // A circuit whose spectrum is not yet allocated (a candidate) is not in the core load: add it,
        // as SNetS v1 does by inserting the evaluated circuit in the link circuit list.
        double load = core.getTotalLaunchPower();
        if (core.getSpectrum().isRangeFree(startSlot, endSlot)) {
            load += candidatePower;
        }
        return calculateLinkAse(link, config, load);
    }

    // ------------------------------------------------------------------
    // Crosstalk
    // ------------------------------------------------------------------

    /**
     * Calculates the Crosstalk noise density (W/Hz) contribution of a circuit to an adjacent core.
     */
    public static double calculateXtContribution(Link link, PhysicalLayerConfig config, Circuit circuit) {
        if (!config.activeXT()) return 0.0;

        // Lobato Model: P_xt = P_j * h * L
        // Noise Density I_xt = P_xt / Bandwidth
        double bandwidth = circuitBandwidth(config, circuit);
        double pLinear = circuitLaunchPower(config, bandwidth);
        
        // h (power-coupling coefficient)
        double hFiber = (2.0 * Math.pow(config.couplingCoefficient(), 2) * config.bendingRadius()) / 
                        (config.propagationConstant() * config.corePitch());
        
        double pXt = pLinear * hFiber * (link.getLength() * 1000.0); // Length in meters

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

    /**
     * Self-channel interference PSD (W/Hz) of a channel of bandwidth {@code bandwidth} (Hz) on a link:
     * {@code N_spans * mu * G^3 * asinh(rho * B^2)}.
     */
    public static double selfChannelInterference(Link link, PhysicalLayerConfig config, double bandwidth) {
        if (!config.activeNLI()) return 0.0;
        double g = circuitLaunchPower(config, bandwidth) / bandwidth;
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
        double g = circuitLaunchPower(config, bandwidth) / bandwidth;
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
        double bandwidth = signalBandwidth(startSlot, endSlot, cp.getGuardBand(), cp.getSlotBandwidth());
        double pLinear = config != null ? circuitLaunchPower(config, bandwidth) : 1E-4;
        double iCh = pLinear / bandwidth;
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
        double bandwidth = signalBandwidth(startSlot, endSlot, cp.getGuardBand(), cp.getSlotBandwidth());
        double pLinear = config != null ? circuitLaunchPower(config, bandwidth) : 1E-4; // 1E-4 W: legacy fallback
        double iCh = pLinear / bandwidth;

        double totalNoiseDensity = 0;
        for (Link link : path.links()) {
            Core core = link.getCore(coreId);
            totalNoiseDensity += config != null
                ? linkAseForPrediction(link, core, config, startSlot, endSlot, pLinear)
                : link.getStaticAseNoise();
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
