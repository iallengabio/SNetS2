package com.snets2;

/**
 * Global constants and toggles for the SNetS2 simulator.
 */
public class SimulationConstants {
    /** Enables/disables detailed debug logs in the console. */
    public static boolean debugEnabled = false;

    /** 
     * Enables/disables strict validation of simulation state and operations.
     * When enabled, the simulator will throw exceptions if illegal operations 
     * are detected (e.g., back-in-time events, double allocation).
     */
    public static boolean strictValidationEnabled = true;
}
