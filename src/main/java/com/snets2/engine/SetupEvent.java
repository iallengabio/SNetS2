package com.snets2.engine;

import com.snets2.SimulationConstants;
import com.snets2.metrics.EnergyConsumptionModel;
import com.snets2.model.AllocationResult;
import com.snets2.model.Circuit;

/**
 * Executes the actual establishment of a lightpath in the network.
 */
public class SetupEvent extends Event {
    private final AllocationResult result;
    private final boolean measured; // false if the originating arrival belonged to the warm-up period

    public SetupEvent(double time, AllocationResult result, boolean measured) {
        super(time);
        this.result = result;
        this.measured = measured;
    }

    @Override
    public void execute(SimulationEngine engine) {
        if (SimulationConstants.debugEnabled) {
            System.out.println(String.format("[DEBUG] t=%.4f | SetupEvent", time));
        }
        
        String circuitId = "c_" + engine.getArrivalCounter();

        // 1. Commit state mutation in the Control Plane
        Circuit circuit = result.toCircuit(circuitId);
        
        if (measured) {
            if (engine.isActiveMetric("CrosstalkStatistics")) {
                recordPhysicalStatistics(engine, circuit);
            }

            if (engine.isActiveMetric("ExternalFragmentation")) {
                engine.getMetricsManager().getExternalFragmentation().recordCircuitSetup(circuit);
            }
            if (engine.isActiveMetric("ModulationUtilization")) {
                engine.getMetricsManager().getModulationUtilization().recordCircuitSetup(circuit);
            }
            if (engine.isActiveMetric("SpectrumSizeStatistics")) {
                engine.getMetricsManager().getSpectrumSize().recordCircuitSetup(circuit);
            }
        }

        // --- TIME-WEIGHTED OBSERVATION (must precede the mutation) ---
        // The state that was valid during (lastObservation, time] is the state BEFORE this setup.
        new ResourceUtilizationObservationEvent(time).execute(engine);

        // --- COMMIT MUTATION ---
        engine.getControlPlane().establishCircuit(circuit);

        // 2. Update energy metrics (dynamic part)
        if (engine.getMetricsManager().getConsumedEnergy() != null && engine.isActiveMetric("ConsumedEnergy")) {
            engine.getMetricsManager().getConsumedEnergy().update(time, engine.isWarmUp());
            double circuitPower = EnergyConsumptionModel.calculateCircuitPower(circuit, engine.getControlPlane().getSlotBandwidth());
            engine.getMetricsManager().getConsumedEnergy().addCircuitPower(circuitPower);
        }

        // 3. Schedule connection departure based on hold time distribution
        double holdTime = engine.nextHoldTime();
        engine.schedule(new DepartureEvent(time + holdTime, circuitId));

        if (measured && engine.isActiveMetric("SimulationMetadata")) {
            engine.getMetricsManager().getSimulationMetadata().recordRequestDuration(holdTime, circuit.getBitRate());
        }
    }

    /**
     * Records the quality of the circuit as it is being established (SNR, XT, launch power, overlaps).
     * Must run before the mutation: predictSNR/predictXT work on the current cache state, which the
     * ControlPlane keeps up to date whenever CrosstalkStatistics is active.
     */
    private static void recordPhysicalStatistics(SimulationEngine engine, Circuit circuit) {
        com.snets2.rmsca.routing.Path path = new com.snets2.rmsca.routing.Path(circuit.getPath());
        int coreId = circuit.getCoreIndices().get(0);
        double snrLinear = com.snets2.metrics.PhysicalLayerModel.predictSNR(
            engine.getControlPlane(), path, circuit.getRegeneratorNodes(),
            coreId, circuit.getStartSlot(), circuit.getEndSlot(),
            circuit.getModulation(), circuit.getBitRate());

        double xtDb = com.snets2.metrics.PhysicalLayerModel.predictXT(
            engine.getControlPlane(), path, circuit.getRegeneratorNodes(),
            coreId, circuit.getStartSlot(), circuit.getEndSlot());

        int overlaps = com.snets2.metrics.PhysicalLayerModel.calculateTotalOverlaps(
            path, coreId, circuit.getStartSlot(), circuit.getEndSlot());

        double powerDbm = -1.0; // Default
        if (engine.getControlPlane().getPhysicalLayerConfig() != null) {
            // Launch power actually used by the circuit (differs from 'power' when the PSD is fixed)
            powerDbm = com.snets2.metrics.PhysicalLayerModel.wattsToDbm(
                com.snets2.metrics.PhysicalLayerModel.circuitLaunchPower(
                    engine.getControlPlane().getPhysicalLayerConfig(), circuit));
        }

        engine.getMetricsManager().getPhysicalLayer().recordCircuitSetup(
            circuit.getSource().getId(), circuit.getDestination().getId(),
            10 * Math.log10(snrLinear), xtDb, powerDbm, overlaps);
    }
}
