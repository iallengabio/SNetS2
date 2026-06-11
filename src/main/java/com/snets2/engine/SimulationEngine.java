package com.snets2.engine;

import com.snets2.SimulationConstants;
import com.snets2.metrics.MetricsManager;
import com.snets2.model.ControlPlane;
import com.snets2.model.NetworkTopology;
import com.snets2.util.RandomGenerator;
import java.util.List;
import java.util.ArrayList;
import java.util.PriorityQueue;

/**
 * The core Discrete Event Simulation (DES) Engine.
 * 
 * <p>It manages the simulation clock, the Future Event List (FEL), and the 
 * overall simulation loop. It provides centralized access to the 
 * {@link NetworkTopology}, {@link ControlPlane}, and {@link RandomGenerator}.</p>
 */
public class SimulationEngine {
    private double currentTime;
    private final PriorityQueue<Event> fel;
    private final NetworkTopology topology;
    private final ControlPlane controlPlane;
    private final RandomGenerator random;
    private final MetricsManager metricsManager;
    
    private int arrivalCounter;
    private final int maxArrivals;
    private final int warmUpRequests;
    
    // Traffic parameters
    private final double lambda; // Arrival rate
    private final double mu;     // Hold rate (usually 1.0)
    private final List<com.snets2.config.TrafficConfig.BitRateConfig> bitRates;
    private final double[] cumulativeWeights;

    /**
     * Initializes the simulation engine.
     *
     * @param topology     The physical network mesh.
     * @param controlPlane The network control plane (contains the RMSCA algorithm).
     * @param maxArrivals  Number of arrivals before stopping the simulation.
     * @param load         Traffic load in Erlangs.
     * @param bitRates     List of bit rates and their weights.
     * @param seed         Random seed for reproducibility.
     */
    public SimulationEngine(NetworkTopology topology, ControlPlane controlPlane, int maxArrivals, int warmUpRequests, double load, 
                            List<com.snets2.config.TrafficConfig.BitRateConfig> bitRates, long seed) {
        this.currentTime = 0;
        this.fel = new PriorityQueue<>();
        this.topology = topology;
        this.controlPlane = controlPlane;
        this.maxArrivals = maxArrivals;
        this.warmUpRequests = warmUpRequests;
        this.arrivalCounter = 0;
        this.random = new RandomGenerator(seed);
        this.metricsManager = new MetricsManager();
        this.metricsManager.initializeEnergyMetric(topology);
        
        List<Double> brValues = new java.util.ArrayList<>();
        if (bitRates == null || bitRates.isEmpty()) {
            brValues.add(100.0);
        } else {
            for (var br : bitRates) {
                brValues.add(br.value());
            }
        }
        this.metricsManager.initializeRelativeFragmentation(controlPlane, brValues);

        this.mu = 1.0;
        this.lambda = load * mu;
        this.bitRates = bitRates != null ? bitRates : new ArrayList<>();
        
        if (!this.bitRates.isEmpty()) {
            this.cumulativeWeights = new double[this.bitRates.size()];
            double sum = 0;
            for (int i = 0; i < this.bitRates.size(); i++) {
                sum += this.bitRates.get(i).weight();
                this.cumulativeWeights[i] = sum;
            }
        } else {
            this.cumulativeWeights = new double[0];
        }
    }

    /**
     * Schedules a new event in the Future Event List (FEL).
     *
     * @param event The event to schedule.
     */
    public void schedule(Event event) {
        fel.add(event);
    }

    /**
     * Starts the simulation loop.
     * Runs until the maximum number of arrivals is reached or the FEL is empty.
     */
    public void run() {
        while (!fel.isEmpty() && arrivalCounter < maxArrivals) {
            Event event = fel.poll();
            if (event == null) break;
            
            // --- STRICT VALIDATION ---
            if (SimulationConstants.strictValidationEnabled) {
                if (event.getTime() < currentTime) {
                    throw new IllegalStateException(String.format(
                        "Causal violation! Attempted to process event of type %s at t=%.4f, but current time is t=%.4f",
                        event.getClass().getSimpleName(), event.getTime(), currentTime));
                }
            }

            // Advance simulation clock
            this.currentTime = event.getTime();
            
            // Process the event
            event.execute(this);
        }

        // Final energy update at simulation end
        if (metricsManager.getConsumedEnergy() != null) {
            metricsManager.getConsumedEnergy().update(currentTime, false);
        }
    }

    /** @return The current simulation time. */
    public double getCurrentTime() { return currentTime; }
    
    /** @return The physical network topology. */
    public NetworkTopology getTopology() { return topology; }
    
    /** @return The network control plane (SSoT and Algorithm access). */
    public ControlPlane getControlPlane() { return controlPlane; }
    
    /** @return The random number generator utility. */
    public RandomGenerator getRandom() { return random; }
    
    /** @return The manager for all simulation metrics. */
    public MetricsManager getMetricsManager() { return metricsManager; }

    /** Increments the total arrival count. */
    public void incrementArrivalCounter() { arrivalCounter++; }
    
    /** @return The number of arrivals processed so far. */
    public int getArrivalCounter() { return arrivalCounter; }

    /** @return true if the simulation is currently in the warm-up transient phase. */
    public boolean isWarmUp() {
        return arrivalCounter < warmUpRequests;
    }

    /** Generates the next inter-arrival time. */
    public double nextArrivalTime() {
        return random.nextExponential(lambda);
    }

    /** Generates the hold time for a connection. */
    public double nextHoldTime() {
        return random.nextExponential(mu);
    }

    /**
     * Picks a random bit rate based on the configured weights.
     * If no bit rates are configured, returns a default value of 100.0.
     * 
     * @return The selected bit rate in Gbps.
     */
    public double nextBitRate() {
        if (bitRates.isEmpty()) return 100.0;
        
        double r = random.nextDouble() * cumulativeWeights[cumulativeWeights.length - 1];
        for (int i = 0; i < cumulativeWeights.length; i++) {
            if (r <= cumulativeWeights[i]) {
                return bitRates.get(i).value();
            }
        }
        return bitRates.get(bitRates.size() - 1).value();
    }

    /** @return The list of configured bit rates. */
    public List<com.snets2.config.TrafficConfig.BitRateConfig> getBitRates() {
        return bitRates;
    }
}
