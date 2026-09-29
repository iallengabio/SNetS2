package com.snets2.rmsca.core;

import com.snets2.model.ControlPlane;
import com.snets2.rmsca.routing.Path;

/**
 * Joint core and spectrum assignment: the (core, interval) candidates of a demand, in the order the RMSCA must
 * validate them. The RMSCA accepts the first candidate that passes the QoT validation.
 *
 * <p>This is the contract of the {@code coreAndSpectrumAssignment} algorithms of SNetS v1 (e.g. ABNE). Algorithms that
 * choose the best candidate by a criterion that the validation cannot change (e.g. the crosstalk of the new circuit)
 * express it through the order. The combination of an {@link ICoreAssignment} and an
 * {@link com.snets2.rmsca.spectrum.ISpectrumAssignment} is adapted by {@link SequentialCoreAndSpectrumAssignment}.</p>
 */
public interface ICoreAndSpectrumAssignment {

    /**
     * Candidates for a demand of {@code numSlots} slots on {@code path}, in validation order. The iteration may be
     * lazy: the RMSCA stops at the first accepted candidate, so stateful or randomized algorithms must not rely on
     * the whole sequence being consumed. Candidates with {@code slots == null} report a core without room.
     */
    Iterable<CoreSpectrumCandidate> candidates(ControlPlane cp, Path path, int numSlots);
}
