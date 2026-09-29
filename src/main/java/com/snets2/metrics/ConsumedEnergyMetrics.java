package com.snets2.metrics;

import com.snets2.output.SimulationResult;
import java.util.Map;

/**
 * Tracks the network's power consumption over time.
 *
 * <p>The network power is a step function P(t) = P_static + sum of the powers of the active circuits, which only
 * changes at setup and teardown instants. The energy is its exact integral over the <b>measured window</b>
 * [T_w, T], where T_w is the instant the warm-up ends ({@link #startMeasurement}) and T the final simulation time;
 * the mean power is E / (T - T_w) and the peak is the maximum of P(t) over the same window
 * (docs/formal_description/06_output_metrics.md, Section 3.6).</p>
 *
 * <p><b>Contract:</b> {@link #update} must be called at time t <em>before</em> any power change at t, so that the
 * power that was valid during (last update, t] is the one integrated (same contract as
 * {@code ResourceUtilizationObservationEvent}).</p>
 */
public class ConsumedEnergyMetrics {
    private final double staticPower;
    private double dynamicPower = 0;

    private double totalEnergyJoule = 0; // Integral of P(t) over [measurementStartTime, lastUpdateTime]
    private double lastUpdateTime = 0;
    private double measurementStartTime = 0; // T_w (0 when there is no warm-up)
    private double peakPower;

    public ConsumedEnergyMetrics(double staticPower) {
        this.staticPower = staticPower;
        this.peakPower = staticPower;
    }

    /**
     * Opens the measured window at {@code time} (end of the warm-up): the energy integrated so far is discarded and
     * the peak restarts from the static power. Without warm-up the window starts at t = 0 and this is never called.
     *
     * @param time Warm-up end instant T_w.
     */
    public void startMeasurement(double time) {
        totalEnergyJoule = 0;
        peakPower = staticPower;
        lastUpdateTime = time;
        measurementStartTime = time;
    }

    /**
     * Integrates the current power over (last update, currentTime]. Must be called before the power changes.
     *
     * @param currentTime Current simulation time.
     */
    public void update(double currentTime) {
        double deltaTime = currentTime - lastUpdateTime;
        if (deltaTime > 0) {
            double currentPower = staticPower + dynamicPower;
            totalEnergyJoule += currentPower * deltaTime;
            if (currentPower > peakPower) {
                peakPower = currentPower;
            }
            lastUpdateTime = currentTime;
        }
    }

    public void addCircuitPower(double circuitPower) {
        this.dynamicPower += circuitPower;
    }

    public void removeCircuitPower(double circuitPower) {
        this.dynamicPower -= circuitPower;
    }

    /** @return Start T_w of the measured window. */
    public double getMeasurementStartTime() { return measurementStartTime; }

    /** @return Energy (J) integrated over the measured window so far. */
    public double getTotalEnergyJoule() { return totalEnergyJoule; }

    /**
     * Mean power over the measured window [T_w, finalTime]: E / (finalTime - T_w). Returns the static power when
     * the window is empty.
     */
    public double getAveragePower(double finalTime) {
        double measuredTime = finalTime - measurementStartTime;
        return measuredTime > 0 ? totalEnergyJoule / measuredTime : staticPower;
    }

    /**
     * Finalizes the average power calculation at the end of simulation.
     */
    public void fillResults(SimulationResult result, Map<String, Object> scenario, int repId, double finalTime) {
        update(finalTime);

        double averagePower = getAveragePower(finalTime);
        String sheet = "ConsumedEnergy";

        result.addValue(sheet, "Average Total Power (W)", Map.of(), scenario, repId, averagePower);
        result.addValue(sheet, "Static Network Power (W)", Map.of(), scenario, repId, staticPower);
        result.addValue(sheet, "Peak Network Power (W)", Map.of(), scenario, repId, peakPower);
        result.addValue(sheet, "Total Energy (J)", Map.of(), scenario, repId, totalEnergyJoule);
    }
}
