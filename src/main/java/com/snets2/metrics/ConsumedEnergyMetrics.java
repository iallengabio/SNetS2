package com.snets2.metrics;

import com.snets2.output.SimulationResult;
import java.util.Map;

/**
 * Tracks the network's power consumption over time.
 * It uses a time-weighted average to calculate the mean power consumption (Watts).
 */
public class ConsumedEnergyMetrics {
    private final double staticPower;
    private double dynamicPower = 0;
    
    private double totalEnergyJoule = 0; // Accumulated Power * delta_t
    private double lastUpdateTime = 0;
    private double peakPower = 0;

    public ConsumedEnergyMetrics(double staticPower) {
        this.staticPower = staticPower;
        this.peakPower = staticPower;
    }

    /**
     * Updates the accumulated energy before a state change.
     */
    public void update(double currentTime, boolean isWarmUp) {
        double deltaTime = currentTime - lastUpdateTime;
        if (deltaTime > 0) {
            if (!isWarmUp) {
                double currentPower = staticPower + dynamicPower;
                totalEnergyJoule += currentPower * deltaTime;
                if (currentPower > peakPower) {
                    peakPower = currentPower;
                }
            }
        }
        lastUpdateTime = currentTime;
    }

    public void addCircuitPower(double circuitPower) {
        this.dynamicPower += circuitPower;
    }

    public void removeCircuitPower(double circuitPower) {
        this.dynamicPower -= circuitPower;
    }

    /**
     * Finalizes the average power calculation at the end of simulation.
     */
    public void fillResults(SimulationResult result, Map<String, Object> scenario, int repId, double finalTime) {
        update(finalTime, false);
        
        double averagePower = finalTime == 0 ? staticPower : totalEnergyJoule / finalTime;
        String sheet = "ConsumedEnergy";
        
        result.addValue(sheet, "Average Total Power (W)", Map.of(), scenario, repId, averagePower);
        result.addValue(sheet, "Static Network Power (W)", Map.of(), scenario, repId, staticPower);
        result.addValue(sheet, "Peak Network Power (W)", Map.of(), scenario, repId, peakPower);
        result.addValue(sheet, "Total Energy (J)", Map.of(), scenario, repId, totalEnergyJoule);
    }
}
