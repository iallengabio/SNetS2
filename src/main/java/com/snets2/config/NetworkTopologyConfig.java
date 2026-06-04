package com.snets2.config;

import java.util.List;

public record NetworkTopologyConfig(
    List<NodeConfig> nodes,
    List<LinkConfig> links,
    List<CoreConfig> cores,
    List<ModulationConfig> modulations
) {}
