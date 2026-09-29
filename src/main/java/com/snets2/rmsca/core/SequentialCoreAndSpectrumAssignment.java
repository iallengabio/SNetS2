package com.snets2.rmsca.core;

import com.snets2.model.ControlPlane;
import com.snets2.rmsca.routing.Path;
import com.snets2.rmsca.spectrum.ISpectrumAssignment;
import java.util.Iterator;
import java.util.List;

/**
 * Adapter of a core order ({@link ICoreAssignment}) and a spectrum policy ({@link ISpectrumAssignment}) to
 * {@link ICoreAndSpectrumAssignment}: one candidate per core, in the order of the core assignment, with the interval
 * proposed by the spectrum policy in that core.
 *
 * <p>The iteration is lazy: the spectrum policy is called for a core only when the RMSCA asks for it, so randomized
 * policies draw exactly the same numbers as when the RMSCA called them directly.</p>
 */
public class SequentialCoreAndSpectrumAssignment implements ICoreAndSpectrumAssignment {

    private final ICoreAssignment coreAssignment;
    private final ISpectrumAssignment spectrumAssignment;

    public SequentialCoreAndSpectrumAssignment(ICoreAssignment coreAssignment, ISpectrumAssignment spectrumAssignment) {
        this.coreAssignment = coreAssignment;
        this.spectrumAssignment = spectrumAssignment;
    }

    public ICoreAssignment getCoreAssignment() { return coreAssignment; }
    public ISpectrumAssignment getSpectrumAssignment() { return spectrumAssignment; }

    @Override
    public Iterable<CoreSpectrumCandidate> candidates(ControlPlane cp, Path path, int numSlots) {
        List<Integer> cores = coreAssignment.selectCores(cp, path, numSlots, spectrumAssignment);
        return () -> new Iterator<>() {
            private final Iterator<Integer> it = cores.iterator();

            @Override
            public boolean hasNext() {
                return it.hasNext();
            }

            @Override
            public CoreSpectrumCandidate next() {
                int core = it.next();
                return new CoreSpectrumCandidate(core, spectrumAssignment.findSlots(cp, path, core, numSlots));
            }
        };
    }
}
