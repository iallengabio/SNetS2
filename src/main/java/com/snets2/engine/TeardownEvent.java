package com.snets2.engine;

import com.snets2.SimulationConstants;

/**
 * Handles the actual release of resources (slots, ports) for a finished connection.
 */
public class TeardownEvent extends Event {
    private final String circuitId;

    public TeardownEvent(double time, String circuitId) {
        super(time);
        this.circuitId = circuitId;
    }

    @Override
    public void execute(SimulationEngine engine) {
        if (com.snets2.SimulationConstants.debugEnabled) {
            System.out.println(String.format("[DEBUG] t=%.4f | TeardownEvent", time));
        }
        // 1. Perform state mutation to free slots and hardware ports
        engine.getControlPlane().teardownCircuit(circuitId);

        // 2. Trigger observation following the organizational pattern
        engine.schedule(new ResourceUtilizationObservationEvent(time));
    }
    }
