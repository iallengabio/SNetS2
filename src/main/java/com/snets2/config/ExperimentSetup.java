package com.snets2.config;

import java.util.List;

/**
 * Root configuration object for a simulation experiment.
 */
public record ExperimentSetup(
    NetworkTopologyConfig networkTopology,
    PhysicalLayerConfig physicalLayer,
    SimulationConfig simulation,
    TrafficConfig traffic,
    ExperimentalPlanningConfig experimentalPlanning
) {}
