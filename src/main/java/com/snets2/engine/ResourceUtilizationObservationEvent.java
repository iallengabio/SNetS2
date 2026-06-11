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
            engine.getMetricsManager().getResourceUtilization()
                  .recordObservation(engine.getControlPlane(), time);
            engine.getMetricsManager().getExternalFragmentation()
                  .recordObservation(engine.getControlPlane(), time);
            engine.getMetricsManager().getRelativeFragmentation()
                  .recordObservation(engine.getControlPlane(), time);
            engine.getMetricsManager().getTransmittersReceiversRegeneratorsUtilization()
                  .recordObservation(engine.getControlPlane(), time);
        } else {
            // Keep lastObservationTime up to date to start window fresh at warm-up end
            engine.getMetricsManager().getResourceUtilization().setLastObservationTime(time);
            engine.getMetricsManager().getExternalFragmentation().setLastObservationTime(time);
            engine.getMetricsManager().getRelativeFragmentation().setLastObservationTime(time);
            engine.getMetricsManager().getTransmittersReceiversRegeneratorsUtilization().setLastObservationTime(time);
        }
    }
}
