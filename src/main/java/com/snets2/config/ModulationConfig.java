package com.snets2.config;

public record ModulationConfig(
    String name,
    double maxRange,
    double M,
    double SNR,
    double XT
) {}
