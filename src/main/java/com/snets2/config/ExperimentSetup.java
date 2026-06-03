package com.snets2.config;

/**
 * Root configuration object for a simulation experiment.
 * It contains the base configuration and the experimental planning details
 * (parameter sweeps and replications).
 */
public record ExperimentSetup(
    NetworkTopologyConfig networkTopology,
    PhysicalLayerConfig physicalLayer,
    SimulationConfig simulation,
    TrafficConfig traffic,
    ExperimentalPlanningConfig experimentalPlanning
) {
    /**
     * Extracts the base simulation configuration as a ScenarioSetup.
     * 
     * @return A new ScenarioSetup instance with the base configuration.
     */
    public ScenarioSetup getBaseScenario() {
        return new ScenarioSetup(networkTopology, physicalLayer, simulation, traffic);
    }
}
