package com.snets2.rmsca.core;

import com.snets2.rmsca.spectrum.SpectrumInterval;

/**
 * A (core, interval) candidate proposed by an {@link ICoreAndSpectrumAssignment}.
 *
 * @param core  core index, the same on every link of the path
 * @param slots interval of slots, or {@code null} if the core was tried but has no free interval for the demand
 */
public record CoreSpectrumCandidate(int core, SpectrumInterval slots) {}
