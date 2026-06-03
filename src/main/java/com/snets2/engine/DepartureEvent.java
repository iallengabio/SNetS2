package com.snets2.engine;

import com.snets2.SimulationConstants;

/**
 * Signals that a connection's hold time has expired.
 */
public class DepartureEvent extends Event {
    private final String circuitId;

    public DepartureEvent(double time, String circuitId) {
        super(time);
        this.circuitId = circuitId;
    }

    @Override
    public void execute(SimulationEngine engine) {
        if (SimulationConstants.debugEnabled) {
            System.out.println(String.format("[DEBUG] t=%.4f | DepartureEvent", time));
        }
        // Schedule immediate teardown to free resources
        engine.schedule(new TeardownEvent(time, circuitId));
    }
}
