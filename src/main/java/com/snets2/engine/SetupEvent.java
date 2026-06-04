package com.snets2.engine;

import com.snets2.SimulationConstants;
import com.snets2.metrics.EnergyConsumptionModel;
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
        
        // --- PRE-ESTABLISHMENT STATS (to capture state before mutation) ---
        // Note: predictSNR/predictXT work on the current cache state.
        // We record the quality of the circuit as it is being established.
        double snrLinear = com.snets2.metrics.PhysicalLayerModel.predictSNR(
            engine.getControlPlane(), new com.snets2.rmsca.routing.Path(circuit.getPath()), 
            circuit.getCoreIndices().get(0), circuit.getStartSlot(), circuit.getEndSlot(), 
            circuit.getModulation(), circuit.getBitRate());
        
        double xtDb = com.snets2.metrics.PhysicalLayerModel.predictXT(
            new com.snets2.rmsca.routing.Path(circuit.getPath()), 
            circuit.getCoreIndices().get(0), circuit.getStartSlot(), circuit.getEndSlot());
            
        int overlaps = com.snets2.metrics.PhysicalLayerModel.calculateTotalOverlaps(
            new com.snets2.rmsca.routing.Path(circuit.getPath()), 
            circuit.getCoreIndices().get(0), circuit.getStartSlot(), circuit.getEndSlot());

        double powerDbm = -1.0; // Default
        if (engine.getControlPlane().getPhysicalLayerConfig() != null) {
            powerDbm = engine.getControlPlane().getPhysicalLayerConfig().power();
        }

        engine.getMetricsManager().getPhysicalLayer().recordCircuitSetup(
            circuit.getSource().getId(), circuit.getDestination().getId(), 
            10 * Math.log10(snrLinear), xtDb, powerDbm, overlaps);

        engine.getMetricsManager().getExternalFragmentation().recordCircuitSetup(circuit);
        engine.getMetricsManager().getModulationUtilization().recordCircuitSetup(circuit);
        engine.getMetricsManager().getSpectrumSize().recordCircuitSetup(circuit);

        // --- COMMIT MUTATION ---
        engine.getControlPlane().establishCircuit(circuit);

        // 2. Update energy metrics (dynamic part)
        if (engine.getMetricsManager().getConsumedEnergy() != null) {
            engine.getMetricsManager().getConsumedEnergy().update(time);
            double circuitPower = EnergyConsumptionModel.calculateCircuitPower(circuit, engine.getControlPlane().getSlotBandwidth());
            engine.getMetricsManager().getConsumedEnergy().addCircuitPower(circuitPower);
        }

        // 3. Schedule connection departure based on hold time distribution
        double holdTime = engine.nextHoldTime();
        engine.schedule(new DepartureEvent(time + holdTime, circuitId));

        // 4. Trigger observation following the organizational pattern
        engine.schedule(new ResourceUtilizationObservationEvent(time));
    }
}
