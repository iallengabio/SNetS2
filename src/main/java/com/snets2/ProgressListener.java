package com.snets2;

/**
 * Interface to receive updates on simulation progress.
 */
public interface ProgressListener {
    /**
     * Called when simulation progress is updated.
     *
     * @param percent   The percentage of completed replications (0.0 to 100.0).
     * @param completed The number of completed replications.
     * @param total     The total number of replications in the sweep.
     */
    void onProgress(double percent, int completed, int total);
}
