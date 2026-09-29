package com.snets2.engine;

import com.snets2.SimulationConstants;
import com.snets2.metrics.BlockingCause;
import com.snets2.model.Node;

/**
 * Records a request blocking in the simulation metrics.
 * 
 * <p>A BlockEvent is generated when the RMSCA algorithm cannot find enough 
 * resources (spectrum, transceivers, or QoT) to satisfy an arrival request.
 * It does not mutate the network state but is vital for performance analysis.</p>
 */
public class BlockEvent extends Event {
    private final Node source;
    private final Node destination;
    private final double bitRate;
    private final BlockingCause cause;
    private final Integer coreId; // Optional: core where blocking happened if applicable
    private final boolean measured; // false if the originating arrival belonged to the warm-up period

    /**
     * Constructs a BlockEvent.
     *
     * @param time        Time of blocking.
     * @param source      Source node of the rejected request.
     * @param destination Destination node.
     * @param bitRate     Requested bit rate.
     * @param cause       Root cause of blocking.
     * @param coreId      Index of the core (optional).
     * @param measured    Whether the originating arrival is counted in the statistics (i.e. not warm-up).
     */
    public BlockEvent(double time, Node source, Node destination, double bitRate, BlockingCause cause, Integer coreId,
                      boolean measured) {
        super(time);
        this.source = source;
        this.destination = destination;
        this.bitRate = bitRate;
        this.cause = cause;
        this.coreId = coreId;
        this.measured = measured;
    }

    @Override
    public void execute(SimulationEngine engine) {
        if (SimulationConstants.debugEnabled) {
            System.out.println(String.format("[DEBUG] t=%.4f | BlockEvent", time));
        }

        // Record metrics
        if (measured && (engine.isActiveMetric("BlockingProbability") || engine.isActiveMetric("BitRateBlockingProbability"))) {
            engine.getMetricsManager().getBitRateBlocking().recordBlock(
                source.getId(), destination.getId(), bitRate, cause, coreId
            );
        }
    }
}
