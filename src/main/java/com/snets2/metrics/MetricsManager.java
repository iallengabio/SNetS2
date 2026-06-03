package com.snets2.metrics;

/**
 * Orchestrates all active metric modules for a simulation replication.
 */
public class MetricsManager {
    private final BitRateBlockingMetrics bitRateBlocking;
    private final ResourceUtilizationMetrics resourceUtilization;

    public MetricsManager() {
        this.bitRateBlocking = new BitRateBlockingMetrics();
        this.resourceUtilization = new ResourceUtilizationMetrics();
    }

    public BitRateBlockingMetrics getBitRateBlocking() {
        return bitRateBlocking;
    }

    public ResourceUtilizationMetrics getResourceUtilization() {
        return resourceUtilization;
    }

    /**
     * Clears all counters for a new replication.
     */
    public void reset() {
        // Re-instantiating is the safest way to reset all maps and counters
        // (Will be refined if performance becomes an issue)
    }
}
