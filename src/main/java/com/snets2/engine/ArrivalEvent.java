package com.snets2.engine;

import com.snets2.SimulationConstants;
import com.snets2.metrics.BlockingCause;
import com.snets2.model.AllocationResult;
import com.snets2.model.Node;
import java.util.List;

/**
 * Represents the arrival of a new connection request in the network.
 * 
 * <p>Upon execution, this event:
 * <ul>
 *     <li>Increments the global arrival counter.</li>
 *     <li>Schedules the next stochastic arrival based on the traffic model.</li>
 *     <li>Invokes the RMSCA algorithm to find a resource allocation.</li>
 *     <li>Schedules a {@link SetupEvent} on success or a {@link BlockEvent} on failure.</li>
 * </ul>
 * </p>
 */
public class ArrivalEvent extends Event {
    private final Node source;
    private final Node destination;
    private final double bitRate;

    /**
     * Constructs an ArrivalEvent.
     *
     * @param time        Time of request arrival.
     * @param source      Source node.
     * @param destination Destination node.
     * @param bitRate     Requested bit rate in Gbps.
     */
    public ArrivalEvent(double time, Node source, Node destination, double bitRate) {
        super(time);
        this.source = source;
        this.destination = destination;
        this.bitRate = bitRate;
    }

    @Override
    public void execute(SimulationEngine engine) {
        if (SimulationConstants.debugEnabled) {
            System.out.println(String.format("[DEBUG] t=%.4f | ArrivalEvent", time));
        }

        // 1. Record metric arrival
        if (!engine.isWarmUp() && (engine.isActiveMetric("BlockingProbability") || engine.isActiveMetric("BitRateBlockingProbability"))) {
            engine.getMetricsManager().getBitRateBlocking().recordArrival(source.getId(), destination.getId(), bitRate);
        }

        // 2. Increment total arrivals processed
        engine.incrementArrivalCounter();

        // 3. Schedule next arrival (stochastic Poisson process)
        List<Node> nodes = engine.getControlPlane().getNodes();
        Node nextSrc = nodes.get(engine.getRandom().nextInt(nodes.size()));
        Node nextDest;
        do {
            nextDest = nodes.get(engine.getRandom().nextInt(nodes.size()));
        } while (nextSrc == nextDest);
        
        double nextTime = time + engine.nextArrivalTime();
        double nextBitRate = engine.nextBitRate();
        engine.schedule(new ArrivalEvent(nextTime, nextSrc, nextDest, nextBitRate));

        // 4. Request allocation from Control Plane / RMSCA (now accessed via ControlPlane)
        AllocationResult result = engine.getControlPlane().getRmsca().allocate(engine.getControlPlane(), source, destination, bitRate);

        if (!result.isBlocked()) {
            // Success: Schedule immediate SetupEvent
            engine.schedule(new SetupEvent(time, result));
        } else {
            // Failure: Schedule immediate BlockEvent with exact cause
            BlockingCause cause = result.blockingCause();
            Integer coreId = result.blockingCoreId();
            engine.schedule(new BlockEvent(time, source, destination, bitRate, cause, coreId));
        }
    }
}
