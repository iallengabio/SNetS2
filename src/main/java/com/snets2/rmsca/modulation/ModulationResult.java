package com.snets2.rmsca.modulation;

import com.snets2.model.ModulationFormat;

/** Result of a modulation selection. */
public record ModulationResult(ModulationFormat format, int numSlots) {}
