package com.snets2.engine;

import com.snets2.SimulationConstants;
import com.snets2.model.AllocationSolution;
import com.snets2.model.Circuit;

/**
 * Executes the actual establishment of a lightpath in the network.
 */
public class SetupEvent extends Event {
    private final AllocationSolution solution;

    public SetupEvent(double time, AllocationSolution solution) {
        super(time);
        this.solution = solution;
    }

    @Override
    public void execute(SimulationEngine engine) {
        if (SimulationConstants.debugEnabled) {
            System.out.println(String.format("[DEBUG] t=%.4f | SetupEvent", time));
        }
        
        String circuitId = "c_" + engine.getArrivalCounter();

        // 1. Commit state mutation in the Control Plane
        Circuit circuit = solution.toCircuit(circuitId);
        engine.getControlPlane().establishCircuit(circuit);

        // 2. Schedule connection departure based on hold time distribution
        double holdTime = engine.nextHoldTime();
        engine.schedule(new DepartureEvent(time + holdTime, circuitId));

        // 3. Trigger observation following the organizational pattern
        engine.schedule(new ResourceUtilizationObservationEvent(time));
    }
}
