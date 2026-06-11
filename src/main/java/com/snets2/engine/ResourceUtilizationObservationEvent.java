package com.snets2.engine;

import com.snets2.SimulationConstants;

/**
 * Special event dedicated to observing network resource utilization.
 * 
 * <p>Following the project's organizational principle, metrics are never called 
 * directly by system classes; they are always invoked via Observation events.</p>
 */
public class ResourceUtilizationObservationEvent extends Event {

    public ResourceUtilizationObservationEvent(double time) {
        super(time);
    }

    @Override
    public void execute(SimulationEngine engine) {
        if (com.snets2.SimulationConstants.debugEnabled) {
            System.out.println(String.format("[DEBUG] t=%.4f | ResourceUtilizationObservation", time));
        }

        if (!engine.isWarmUp()) {
            // Perform time-weighted observations
            if (engine.isActiveMetric("SpectrumUtilization")) {
                engine.getMetricsManager().getResourceUtilization()
                      .recordObservation(engine.getControlPlane(), time);
            }
            if (engine.isActiveMetric("ExternalFragmentation")) {
                engine.getMetricsManager().getExternalFragmentation()
                      .recordObservation(engine.getControlPlane(), time);
            }
            if (engine.isActiveMetric("RelativeFragmentation")) {
                engine.getMetricsManager().getRelativeFragmentation()
                      .recordObservation(engine.getControlPlane(), time);
            }
            if (engine.isActiveMetric("TransmittersReceiversRegeneratorsUtilization")) {
                engine.getMetricsManager().getTransmittersReceiversRegeneratorsUtilization()
                      .recordObservation(engine.getControlPlane(), time);
            }
        } else {
            // Keep lastObservationTime up to date to start window fresh at warm-up end
            if (engine.isActiveMetric("SpectrumUtilization")) {
                engine.getMetricsManager().getResourceUtilization().setLastObservationTime(time);
            }
            if (engine.isActiveMetric("ExternalFragmentation")) {
                engine.getMetricsManager().getExternalFragmentation().setLastObservationTime(time);
            }
            if (engine.isActiveMetric("RelativeFragmentation")) {
                engine.getMetricsManager().getRelativeFragmentation().setLastObservationTime(time);
            }
            if (engine.isActiveMetric("TransmittersReceiversRegeneratorsUtilization")) {
                engine.getMetricsManager().getTransmittersReceiversRegeneratorsUtilization().setLastObservationTime(time);
            }
        }
    }
}
