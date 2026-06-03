package com.snets2.config;

import java.util.List;
import java.util.Map;

public record TrafficConfig(
    String loadDistributionPerPair,
    Double load,
    Map<String, Double> loadByPair,
    List<BitRateConfig> bitRates
) {
    public record BitRateConfig(double value, double weight) {}
}
