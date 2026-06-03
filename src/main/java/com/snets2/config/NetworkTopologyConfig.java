package com.snets2.config;

import java.util.List;

public record NetworkTopologyConfig(
    List<NodeConfig> nodes,
    List<LinkConfig> links,
    List<CoreConfig> cores,
    List<ModulationConfig> modulations
) {}

record NodeConfig(
    String id,
    int tx,
    int rx,
    int regenerators
) {}

record LinkConfig(
    String source,
    String destination,
    double length
) {}

record CoreConfig(
    int id,
    List<Integer> adjacentCores
) {}

record ModulationConfig(
    String name,
    double maxRange,
    double M,
    double SNR,
    double XT
) {}
