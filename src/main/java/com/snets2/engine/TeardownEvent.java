package com.snets2.engine;

import com.snets2.metrics.EnergyConsumptionModel;
import com.snets2.model.Circuit;

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

        // 1. Update energy metrics before circuit is gone (to get its power)
        if (engine.getMetricsManager().getConsumedEnergy() != null) {
            engine.getMetricsManager().getConsumedEnergy().update(time, engine.isWarmUp());
            
            // Find circuit in active list to calculate its power
            Circuit circuit = engine.getControlPlane().getActiveCircuits().stream()
                .filter(c -> c.getId().equals(circuitId))
                .findFirst()
                .orElse(null);
            
            if (circuit != null) {
                double circuitPower = EnergyConsumptionModel.calculateCircuitPower(circuit, engine.getControlPlane().getSlotBandwidth());
                engine.getMetricsManager().getConsumedEnergy().removeCircuitPower(circuitPower);
            }
        }

        // 2. Perform state mutation to free slots and hardware ports
        engine.getControlPlane().teardownCircuit(circuitId);

        // 3. Trigger observation following the organizational pattern
        engine.schedule(new ResourceUtilizationObservationEvent(time));
    }
}
