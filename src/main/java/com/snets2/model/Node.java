package com.snets2.model;

import com.snets2.SimulationConstants;

/**
 * Represents a Reconfigurable Optical Add-Drop Multiplexer (ROADM) node.
 * 
 * <p>The node manages local hardware resources: Transmitters (Tx) for signal ingress,
 * Receivers (Rx) for signal egress, and Regenerators for O-E-O conversion to restore signal quality.
 * These resources are finite and their exhaustion leads to blocking.</p>
 */
public class Node {
    private final String id;
    private final int totalTx;
    private final int totalRx;
    private final int totalRegenerators;
    
    private int availableTx;
    private int availableRx;
    private int availableRegenerators;

    /**
     * Constructs a Node with specified resource capacities.
     *
     * @param id           Unique identifier for the node.
     * @param tx           Total number of Bandwidth Variable Transceivers (BVTs) for transmission.
     * @param rx           Total number of BVTs for reception.
     * @param regenerators Total number of regenerators available for signal restoration.
     */
    public Node(String id, int tx, int rx, int regenerators) {
        this.id = id;
        this.totalTx = tx;
        this.totalRx = rx;
        this.totalRegenerators = regenerators;
        this.availableTx = tx;
        this.availableRx = rx;
        this.availableRegenerators = regenerators;
    }

    /**
     * Gets the node identifier.
     *
     * @return Node ID.
     */
    public String getId() {
        return id;
    }

    /** @return true if there is at least one transmitter available. */
    public boolean hasAvailableTx() {
        return availableTx > 0;
    }

    /**
     * Decrements the available transmitter count.
     * @throws IllegalStateException if no transmitters are left.
     */
    public void consumeTx() {
        if (availableTx <= 0) {
            throw new IllegalStateException("No Tx available on node " + id);
        }
        availableTx--;
    }

    /**
     * Increments the available transmitter count.
     * @throws IllegalStateException if the count exceeds the total capacity.
     */
    public void releaseTx() {
        if (SimulationConstants.strictValidationEnabled) {
            if (availableTx >= totalTx) {
                throw new IllegalStateException("Tx overflow on node " + id + ": attempted to release but already at full capacity (" + totalTx + ")");
            }
        }
        availableTx++;
    }

    /** @return true if there is at least one receiver available. */
    public boolean hasAvailableRx() {
        return availableRx > 0;
    }

    /**
     * Decrements the available receiver count.
     * @throws IllegalStateException if no receivers are left.
     */
    public void consumeRx() {
        if (availableRx <= 0) {
            throw new IllegalStateException("No Rx available on node " + id);
        }
        availableRx--;
    }

    /**
     * Increments the available receiver count.
     * @throws IllegalStateException if the count exceeds the total capacity.
     */
    public void releaseRx() {
        if (SimulationConstants.strictValidationEnabled) {
            if (availableRx >= totalRx) {
                throw new IllegalStateException("Rx overflow on node " + id + ": attempted to release but already at full capacity (" + totalRx + ")");
            }
        }
        availableRx++;
    }

    /**
     * Checks if a specific amount of regenerators is available.
     * @param amount The number of regenerators needed.
     * @return true if enough regenerators are free.
     */
    public boolean hasAvailableRegenerators(int amount) {
        return availableRegenerators >= amount;
    }

    /**
     * Decrements the available regenerator count by the specified amount.
     * @param amount Number of regenerators to consume.
     * @throws IllegalStateException if there are not enough regenerators.
     */
    public void consumeRegenerators(int amount) {
        if (availableRegenerators < amount) {
            throw new IllegalStateException("Not enough regenerators on node " + id + ". Requested: " + amount + ", Available: " + availableRegenerators);
        }
        availableRegenerators -= amount;
    }

    /**
     * Increments the available regenerator count.
     * @param amount Number of regenerators to return to the pool.
     * @throws IllegalStateException if the count exceeds the total capacity.
     */
    public void releaseRegenerators(int amount) {
        if (SimulationConstants.strictValidationEnabled) {
            if (availableRegenerators + amount > totalRegenerators) {
                throw new IllegalStateException("Regenerator overflow on node " + id + ": capacity is " + totalRegenerators + ", current " + availableRegenerators + " + release " + amount);
            }
        }
        availableRegenerators += amount;
    }

    public int getAvailableTx() { return availableTx; }
    public int getAvailableRx() { return availableRx; }
    public int getAvailableRegenerators() { return availableRegenerators; }
    
    public int getTotalTx() { return totalTx; }
    public int getTotalRx() { return totalRx; }
    public int getTotalRegenerators() { return totalRegenerators; }
}
