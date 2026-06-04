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
        
        double bandwidth = (circuit.getEndSlot() - circuit.getStartSlot() + 1) * config.bvtSpectralWidth();
        
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
        
        double pLinear = Math.pow(10, config.power() / 10.0) * 1E-3;
        double gamma = config.fiberNonlinearity();
        double alpha = config.fiberLoss() / (10.0 * Math.log10(Math.E) * 1000.0); // 1/m
        double beta2 = Math.abs(-1.0 * config.fiberDispersion() * Math.pow(3E8 / config.centerFrequency(), 2) / (2.0 * Math.PI * 3E8));
        
        double bandwidth = (circuit.getEndSlot() - circuit.getStartSlot() + 1) * config.bvtSpectralWidth();
        double gSignal = pLinear / bandwidth;

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
        double totalNoiseDensity = 0;
        double totalAseDensity = 0;
        
        for (Link link : path.links()) {
            Core core = link.getCore(coreId);
            totalAseDensity += link.getStaticAseNoise();
            totalNoiseDensity += core.getAverageNliNoise(startSlot, endSlot);
            totalNoiseDensity += core.getAverageXtNoise(startSlot, endSlot);
        }

        totalNoiseDensity += totalAseDensity;

        // Signal Power Density
        // I_ch = P_total / Bandwidth
        double pLinear = 1E-4; // Default fallback
        if (cp.getPhysicalLayerConfig() != null) {
            pLinear = Math.pow(10, cp.getPhysicalLayerConfig().power() / 10.0) * 1E-3; // dBm to Watts
        }
        
        double bandwidth = (endSlot - startSlot + 1) * cp.getSlotBandwidth();
        double iCh = pLinear / bandwidth;

        return iCh / Math.max(1E-30, totalNoiseDensity);
    }

    /**
     * Predicts the SNR (Linear) for a proposed allocation excluding inter-core crosstalk.
     */
    public static double predictSnrWithoutXt(ControlPlane cp, Path path, int coreId, int startSlot, int endSlot, ModulationFormat mod, double bitRate) {
        double totalNoiseDensity = 0;
        double totalAseDensity = 0;
        
        for (Link link : path.links()) {
            Core core = link.getCore(coreId);
            totalAseDensity += link.getStaticAseNoise();
            totalNoiseDensity += core.getAverageNliNoise(startSlot, endSlot);
        }

        totalNoiseDensity += totalAseDensity;

        // Signal Power Density
        double pLinear = 1E-4;
        if (cp.getPhysicalLayerConfig() != null) {
            pLinear = Math.pow(10, cp.getPhysicalLayerConfig().power() / 10.0) * 1E-3;
        }
        
        double bandwidth = (endSlot - startSlot + 1) * cp.getSlotBandwidth();
        double iCh = pLinear / bandwidth;

        return iCh / Math.max(1E-30, totalNoiseDensity);
    }
}
