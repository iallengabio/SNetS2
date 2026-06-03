package com.snets2.rmsca;

import com.snets2.model.ModulationFormat;

/** Result of a modulation selection. */
public record ModulationResult(ModulationFormat format, int numSlots) {}
