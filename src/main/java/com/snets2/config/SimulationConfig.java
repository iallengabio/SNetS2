package com.snets2.config;

import java.util.Map;

public record SimulationConfig(
    int requests,
    int totalSlots,
    String routing,
    String kRouting,
    String spectrumAssignment,
    String coreAndSpectrumAssignment,
    String integratedRmlsa,
    String modulationSelection,
    String grooming,
    String reallocation,
    String powerAssignment,
    String regeneratorAssignment,
    int networkType,
    int threads,
    Map<String, Boolean> activeMetrics
) {}
