package com.snets2.rmsca.core;

import com.snets2.model.ControlPlane;
import com.snets2.rmsca.routing.Path;
import com.snets2.rmsca.spectrum.ISpectrumAssignment;
import java.util.List;

/** Interface for Core Assignment algorithms. */
public interface ICoreAssignment {
    /**
     * Selects candidate cores for the given path, in priority order.
     */
    List<Integer> selectCores(ControlPlane cp, Path path);

    /**
     * Selects candidate cores for a request that needs {@code numSlots} slots, in priority order.
     *
     * <p>Slot-aware strategies (e.g. {@link XtAwareCoreAssignment}) use the demand size and the
     * configured spectrum policy to estimate the interval each core would receive. The default
     * ignores both and delegates to {@link #selectCores(ControlPlane, Path)}.</p>
     *
     * @param spectrumAssignment spectrum policy used by the RMSCA (may be {@code null})
     */
    default List<Integer> selectCores(ControlPlane cp, Path path, int numSlots, ISpectrumAssignment spectrumAssignment) {
        return selectCores(cp, path);
    }
}
