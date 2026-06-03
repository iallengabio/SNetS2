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
        if (SimulationConstants.debugEnabled) {
            System.out.println(String.format("[DEBUG] t=%.4f | ResourceUtilizationObservation", time));
        }

        // Perform time-weighted observation
        engine.getMetricsManager().getResourceUtilization()
              .recordObservation(engine.getControlPlane(), time);
    }
}
