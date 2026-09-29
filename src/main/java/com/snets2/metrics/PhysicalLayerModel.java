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
     * Effective (Nyquist) signal bandwidth of a circuit, excluding guard bands:
     * B_si = R_b (1 + r_FEC) / (N_pol log2 M).
     *
     * <p>Same definition as {@code Modulation.getBandwidthFromBitRate} in SNetS v1.</p>
     *
     * @param bitRate Requested bit rate in Gbps.
     * @return Bandwidth in Hz.
     */
    public static double effectiveBandwidth(PhysicalLayerConfig config, ModulationFormat mod, double bitRate) {
        return (bitRate * 1E9 * (1.0 + config.rateOfFEC()))
                / (config.effectivePolarizationModes() * mod.getBitsPerSymbol());
    }

    /**
     * Launch power of a circuit (W).
     *
     * <ul>
     *   <li>Variable PSD ({@code fixedPowerSpectralDensity = false}): every circuit is launched with
     *       {@code power}, so wider circuits have a lower PSD.</li>
     *   <li>Fixed PSD ({@code fixedPowerSpectralDensity = true}): {@code power} is the power in the
     *       reference bandwidth B_ref and every circuit keeps the PSD P_ref / B_ref, i.e.
     *       P_i = (P_ref / B_ref) B_si ("flat PSD" launch, as assumed by the GN-model closed forms in
     *       Poggiolini, JLT 2012, and Johannisson &amp; Agrell, JLT 2014).</li>
     * </ul>
     *
     * <p>Follows {@code PhysicalLayer.getCircuitLaunchPower} of SNetS v1.</p>
     */
    public static double circuitLaunchPower(PhysicalLayerConfig config, ModulationFormat mod, double bitRate) {
        double referencePower = dbmToWatts(config.power());
        if (!config.fixedPowerSpectralDensity()) {
            return referencePower;
        }
        double psd = referencePower / config.referenceBandwidthForPowerSpectralDensity();
        return psd * effectiveBandwidth(config, mod, bitRate);
    }

    /**
     * Signal power spectral density I = P_i / B_si (W/Hz). With a fixed PSD this equals P_ref / B_ref.
     */
    public static double signalPsd(PhysicalLayerConfig config, ModulationFormat mod, double bitRate) {
        return circuitLaunchPower(config, mod, bitRate) / effectiveBandwidth(config, mod, bitRate);
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

        int nLine = numberOfLineAmplifiers(link.getLength(), config.spanLength());
        double lastSegment = link.getLength() - nLine * config.spanLength();

        double boosterGainDb = 3.0 * config.switchInsertionLoss();
        double lineGainDb = config.fiberLoss() * config.spanLength();
        double preGainDb = config.fiberLoss() * lastSegment;

        int stages = nLine + 2;
        double ase = 0.0;
        double rho = 1.0;
        for (int k = 0; k < stages; k++) {
            double g0Db = (k == 0) ? boosterGainDb : (k == stages - 1) ? preGainDb : lineGainDb;
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
        // Noise Density I_xt = P_xt / B_si
        double pLinear = circuitLaunchPower(config, circuit.getModulation(), circuit.getBitRate());
        
        // h (power-coupling coefficient)
        double hFiber = (2.0 * Math.pow(config.couplingCoefficient(), 2) * config.bendingRadius()) / 
                        (config.propagationConstant() * config.corePitch());
        
        double pXt = pLinear * hFiber * (link.getLength() * 1000.0); // Length in meters
        
        double bandwidth = effectiveBandwidth(config, circuit.getModulation(), circuit.getBitRate());
        
        return pXt / bandwidth;
    }

    /**
     * Generates a noise mask for NLI contribution in the same core.
     * 
     * <p>Note: For true O(S) prediction, we assume the NLI added to slot 's' 
     * by a circuit at 'f_c' is G_nli(s, f_c).</p>
     */
    public static double[] generateNliMask(Link link, PhysicalLayerConfig config, Circuit circuit, int totalSlots) {
        double[] mask = new double[totalSlots];
        if (!config.activeNLI()) return mask;

        // GN-Model simplified: I_nli is highest at the circuit's frequency and decays.
        // For this version, we will use a very simplified version where it adds noise 
        // to all slots in the core based on the Johannisson/Habibi curves.
        
        double gamma = config.fiberNonlinearity();
        double alpha = config.fiberLoss() / (10.0 * Math.log10(Math.E) * 1000.0); // 1/m
        double beta2 = Math.abs(-1.0 * config.fiberDispersion() * Math.pow(3E8 / config.centerFrequency(), 2) / (2.0 * Math.PI * 3E8));
        
        double bandwidth = (circuit.getEndSlot() - circuit.getStartSlot() + 1) * config.bvtSpectralWidth();
        double gSignal = signalPsd(config, circuit.getModulation(), circuit.getBitRate());

        // mi calculation from Johannisson
        double mi = Math.pow(gSignal, 3) * (3.0 * Math.pow(gamma, 2)) / (2.0 * Math.PI * alpha * beta2);
        
        int centerSlot = (circuit.getStartSlot() + circuit.getEndSlot()) / 2;

        for (int s = 0; s < totalSlots; s++) {
            double deltaF = Math.abs(s - centerSlot) * config.bvtSpectralWidth();
            if (deltaF == 0) deltaF = config.bvtSpectralWidth() / 10.0;
            
            // Logarithmic decay of NLI interference with frequency distance
            double ro = Math.pow(bandwidth, 2) * Math.pow(Math.PI, 2) * beta2 / (2.0 * alpha);
            double contribution = mi * Math.log(1.0 + (ro / Math.pow(deltaF / bandwidth, 2)));
            
            mask[s] = Math.max(0, contribution / bandwidth); // Density
        }

        return mask;
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
     * Calculates the current average XT (dB) for a proposed allocation.
     */
    public static double predictXT(Path path, int coreId, int startSlot, int endSlot) {
        double totalXtDensity = 0;
        for (Link link : path.links()) {
            Core core = link.getCore(coreId);
            totalXtDensity += core.getAverageXtNoise(startSlot, endSlot);
        }
        return 10 * Math.log10(Math.max(1E-30, totalXtDensity));
    }

    /**
     * Predicts the OSNR (Linear) for a proposed allocation.
     */
    public static double predictSNR(ControlPlane cp, Path path, int coreId, int startSlot, int endSlot, ModulationFormat mod, double bitRate) {
        return predictSnr(cp, path, coreId, startSlot, endSlot, mod, bitRate, true);
    }

    /**
     * Predicts the SNR (Linear) for a proposed allocation excluding inter-core crosstalk.
     */
    public static double predictSnrWithoutXt(ControlPlane cp, Path path, int coreId, int startSlot, int endSlot, ModulationFormat mod, double bitRate) {
        return predictSnr(cp, path, coreId, startSlot, endSlot, mod, bitRate, false);
    }

    /**
     * SNR = I / (I_ASE + I_NLI [+ I_XT]), with I the signal PSD (see {@link #signalPsd}).
     */
    private static double predictSnr(ControlPlane cp, Path path, int coreId, int startSlot, int endSlot,
                                     ModulationFormat mod, double bitRate, boolean includeXt) {
        PhysicalLayerConfig config = cp.getPhysicalLayerConfig();

        double iCh;
        double candidatePower;
        if (config != null) {
            candidatePower = circuitLaunchPower(config, mod, bitRate);
            iCh = signalPsd(config, mod, bitRate);
        } else {
            candidatePower = 1E-4; // Default fallback
            iCh = candidatePower / ((endSlot - startSlot + 1) * cp.getSlotBandwidth());
        }

        double totalNoiseDensity = 0;
        for (Link link : path.links()) {
            Core core = link.getCore(coreId);
            totalNoiseDensity += config != null
                ? linkAseForPrediction(link, core, config, startSlot, endSlot, candidatePower)
                : link.getStaticAseNoise();
            totalNoiseDensity += core.getAverageNliNoise(startSlot, endSlot);
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

    public static double predictXT(Path path, List<Node> regenerators, int coreId, int startSlot, int endSlot) {
        List<Path> segments = getPathSegments(path, regenerators);
        double maxXtDb = -Double.MAX_VALUE;
        for (Path segment : segments) {
            double xtDb = predictXT(segment, coreId, startSlot, endSlot);
            if (xtDb > maxXtDb) {
                maxXtDb = xtDb;
            }
        }
        return maxXtDb;
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
