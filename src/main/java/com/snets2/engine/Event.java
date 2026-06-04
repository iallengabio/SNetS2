package com.snets2.engine;

/**
 * Base abstract class for all discrete events in the simulation.
 * 
 * <p>
 * Events are ordered in the Future Event List (FEL) by their occurrence time.
 * Each event encapsulates the logic to mutate the system state or schedule
 * future events.
 * </p>
 */
public abstract class Event implements Comparable<Event> {
    protected final double time;

    /**
     * Constructs an event to occur at a specific simulation time.
     *
     * @param time The scheduled time for the event.
     */
    protected Event(double time) {
        this.time = time;
    }

    /**
     * Returns the time at which this event is scheduled to occur.
     *
     * @return Simulation time.
     */
    public double getTime() {
        return time;
    }

    /**
     * Compares events based on their occurrence time for the Priority Queue (FEL).
     */
    @Override
    public int compareTo(Event other) {
        return Double.compare(this.time, other.time);
    }

    /**
     * Executes the event's logic within the context of the simulation engine.
     *
     * @param engine The {@link SimulationEngine} instance.
     */
    public abstract void execute(SimulationEngine engine);
}
