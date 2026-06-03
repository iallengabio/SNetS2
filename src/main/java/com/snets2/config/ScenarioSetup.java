package com.snets2.config;

/**
 * Represents a concrete configuration for a single simulation scenario.
 * It contains all parameters necessary to run the simulation, but excludes
 * the experimental planning details (parameter sweeps, replications, etc.).
 */
public record ScenarioSetup(
    NetworkTopologyConfig networkTopology,
    PhysicalLayerConfig physicalLayer,
    SimulationConfig simulation,
    TrafficConfig traffic
) {}
