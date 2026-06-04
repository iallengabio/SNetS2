package com.snets2.metrics;

import com.snets2.model.NetworkTopology;
import com.snets2.model.ControlPlane;
import java.util.List;

/**
 * Point of access for all metric collection modules.
 */
public class MetricsManager {

    private final BitRateBlockingMetrics bitRateBlocking;
    private final ResourceUtilizationMetrics resourceUtilization;
    private final PhysicalLayerMetrics physicalLayer;
    private ConsumedEnergyMetrics consumedEnergy;

    private final ExternalFragmentationMetrics externalFragmentation;
    private final RelativeFragmentationMetrics relativeFragmentation;
    private final ModulationUtilizationMetrics modulationUtilization;
    private final SpectrumSizeMetrics spectrumSize;
    private final TransmittersReceiversRegeneratorsUtilizationMetrics transmittersReceiversRegeneratorsUtilization;

    public MetricsManager() {
        this.bitRateBlocking = new BitRateBlockingMetrics();
        this.resourceUtilization = new ResourceUtilizationMetrics();
        this.physicalLayer = new PhysicalLayerMetrics();
        this.externalFragmentation = new ExternalFragmentationMetrics();
        this.relativeFragmentation = new RelativeFragmentationMetrics();
        this.modulationUtilization = new ModulationUtilizationMetrics();
        this.spectrumSize = new SpectrumSizeMetrics();
        this.transmittersReceiversRegeneratorsUtilization = new TransmittersReceiversRegeneratorsUtilizationMetrics();
    }

    /**
     * Initializes the energy metric with the topology-specific static power.
     */
    public void initializeEnergyMetric(NetworkTopology topology) {
        double staticPower = EnergyConsumptionModel.calculateStaticPower(topology);
        this.consumedEnergy = new ConsumedEnergyMetrics(staticPower);
    }

    /**
     * Initializes the relative fragmentation metric with possible slot demand sizes.
     */
    public void initializeRelativeFragmentation(ControlPlane cp, List<Double> bitRates) {
        this.relativeFragmentation.initialize(cp, bitRates);
    }

    public BitRateBlockingMetrics getBitRateBlocking() { return bitRateBlocking; }
    public ResourceUtilizationMetrics getResourceUtilization() { return resourceUtilization; }
    public PhysicalLayerMetrics getPhysicalLayer() { return physicalLayer; }
    
    /**
     * @return The energy metrics module, or null if not initialized.
     */
    public ConsumedEnergyMetrics getConsumedEnergy() { return consumedEnergy; }

    public ExternalFragmentationMetrics getExternalFragmentation() { return externalFragmentation; }
    public RelativeFragmentationMetrics getRelativeFragmentation() { return relativeFragmentation; }
    public ModulationUtilizationMetrics getModulationUtilization() { return modulationUtilization; }
    public SpectrumSizeMetrics getSpectrumSize() { return spectrumSize; }
    public TransmittersReceiversRegeneratorsUtilizationMetrics getTransmittersReceiversRegeneratorsUtilization() {
        return transmittersReceiversRegeneratorsUtilization;
    }
}
