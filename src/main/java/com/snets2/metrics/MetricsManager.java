package com.snets2.metrics;

import com.snets2.model.NetworkTopology;

/**
 * Point of access for all metric collection modules.
 */
public class MetricsManager {

    private final BitRateBlockingMetrics bitRateBlocking;
    private final ResourceUtilizationMetrics resourceUtilization;
    private ConsumedEnergyMetrics consumedEnergy;

    public MetricsManager() {
        this.bitRateBlocking = new BitRateBlockingMetrics();
        this.resourceUtilization = new ResourceUtilizationMetrics();
    }

    /**
     * Initializes the energy metric with the topology-specific static power.
     */
    public void initializeEnergyMetric(NetworkTopology topology) {
        double staticPower = EnergyConsumptionModel.calculateStaticPower(topology);
        this.consumedEnergy = new ConsumedEnergyMetrics(staticPower);
    }

    public BitRateBlockingMetrics getBitRateBlocking() { return bitRateBlocking; }
    public ResourceUtilizationMetrics getResourceUtilization() { return resourceUtilization; }
    
    /**
     * @return The energy metrics module, or null if not initialized.
     */
    public ConsumedEnergyMetrics getConsumedEnergy() { return consumedEnergy; }
}
