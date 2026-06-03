package com.snets2;

import com.snets2.config.ConfigLoader;
import com.snets2.config.ExperimentSetup;
import com.snets2.config.ScenarioSetup;
import com.snets2.config.TopologyMapper;
import com.snets2.engine.ArrivalEvent;
import com.snets2.engine.ResourceUtilizationObservationEvent;
import com.snets2.engine.SimulationEngine;
import com.snets2.model.ControlPlane;
import com.snets2.model.NetworkTopology;
import com.snets2.model.Node;
import com.snets2.output.ExcelExporter;
import com.snets2.output.SimulationResult;
import com.snets2.rmsca.AlgorithmFactory;
import com.snets2.rmsca.IRMSCA;
import com.snets2.rmsca.StandardIntegratedRMSCA;

import java.io.File;
import java.util.*;

/**
 * Orchestrates the execution of a complete experiment, including 
 * parameter sweeps and multiple replications.
 */
public class ExperimentalPlanner {
    private final File experimentFolder;
    private ExperimentSetup baseSetup;
    private SimulationResult aggregatedResult;

    public ExperimentalPlanner(File folder) {
        this.experimentFolder = folder;
    }

    public void run() throws Exception {
        File setupFile = new File(experimentFolder, "setup.json");
        this.baseSetup = ConfigLoader.load(setupFile);

        System.out.println("Starting Experimental Planning for: " + experimentFolder.getName());
        
        Map<String, List<Object>> sweepVariables = baseSetup.experimentalPlanning().getVariables();
        List<Map<String, Object>> scenarios = generateParameterSweep(sweepVariables);
        int replications = baseSetup.experimentalPlanning().getReplications();

        System.out.println("Generated " + scenarios.size() + " unique scenarios.");
        this.aggregatedResult = new SimulationResult(replications);

        ScenarioSetup baseScenario = baseSetup.getBaseScenario();

        for (Map<String, Object> scenario : scenarios) {
            runScenario(baseScenario, scenario, replications);
        }

        // Export Results
        System.out.println("\nExporting results to Excel...");
        ExcelExporter exporter = new ExcelExporter();
        File outputFile = new File(experimentFolder, "results.xlsx");
        exporter.export(aggregatedResult, outputFile.toPath());
        System.out.println("Results saved to: " + outputFile.getAbsolutePath());
    }

    private void runScenario(ScenarioSetup baseScenario, Map<String, Object> scenarioMap, int replications) throws Exception {
        System.out.println("\n--- SCENARIO: " + scenarioMap + " ---");
        
        // Generate the concrete setup for this scenario by applying dynamic overrides
        ScenarioSetup scenarioSetup = ConfigLoader.applyOverrides(baseScenario, scenarioMap);
        
        for (int rep = 0; rep < replications; rep++) {
            runReplication(rep, scenarioSetup, scenarioMap);
        }
    }

    private void runReplication(int repId, ScenarioSetup setup, Map<String, Object> scenarioMap) throws Exception {
        System.out.print(String.format("  > Rep %d... ", repId));
        
        // 1. Map Topology using scenario-specific values
        NetworkTopology topology = TopologyMapper.map(
            setup.networkTopology(), 
            setup.physicalLayer(),
            setup.simulation().totalSlots()
        );

        // 2. Instantiate Algorithm Chain using scenario-specific values
        IRMSCA rmsca = AlgorithmFactory.createIntegrated(setup.simulation().integratedRMSCA());

        if (rmsca instanceof StandardIntegratedRMSCA standard) {
            standard.setRouting(AlgorithmFactory.createRouting(setup.simulation().routing()));
            standard.setModulationSelection(AlgorithmFactory.createModulation(setup.simulation().modulationSelection()));
            standard.setCoreAssignment(AlgorithmFactory.createCore(setup.simulation().coreAndSpectrumAssignment()));
            standard.setSpectrumAssignment(AlgorithmFactory.createSpectrum(setup.simulation().spectrumAssignment()));
        }

        // 3. Initialize Control Plane
        ControlPlane cp = new ControlPlane(
            topology, 
            rmsca, 
            setup.physicalLayer().bvtSpectralWidth(),
            setup.physicalLayer().guardBand()
        );

        // 4. Initialize Engine
        double load = setup.traffic().load() != null ? setup.traffic().load() : 1.0;
        SimulationEngine engine = new SimulationEngine(
            topology, 
            cp, 
            setup.simulation().requests(), 
            load, 
            setup.traffic().bitRates(),
            repId // Seed
        );

        // 5. Seed First Event
        List<Node> nodes = topology.nodes();
        Node src = nodes.get(engine.getRandom().nextInt(nodes.size()));
        Node dest;
        do {
            dest = nodes.get(engine.getRandom().nextInt(nodes.size()));
        } while (src == dest);
        
        double firstBitRate = engine.nextBitRate();
        engine.schedule(new ArrivalEvent(0.0, src, dest, firstBitRate));

        // 6. Schedule periodic observations
        engine.schedule(new ResourceUtilizationObservationEvent(0.0));

        // 7. Run
        engine.run();

        // 8. Collect results into the aggregator (using the scenario map for identifying the row)
        engine.getMetricsManager().getBitRateBlocking().fillResults(aggregatedResult, scenarioMap, repId);
        engine.getMetricsManager().getResourceUtilization().fillResults(aggregatedResult, scenarioMap, repId);
        
        if (engine.getMetricsManager().getConsumedEnergy() != null) {
            engine.getMetricsManager().getConsumedEnergy().fillResults(aggregatedResult, scenarioMap, repId, engine.getCurrentTime());
        }

        double bp = engine.getMetricsManager().getBitRateBlocking().getGeneralBlockingProbability();
        System.out.println(String.format("BP: %.4e", bp));
    }

    /**
     * Generates a Cartesian product of all variables for the parameter sweep.
     */
    private List<Map<String, Object>> generateParameterSweep(Map<String, List<Object>> variables) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (variables.isEmpty()) {
            result.add(new HashMap<>());
            return result;
        }

        List<String> keys = new ArrayList<>(variables.keySet());
        generateCombinations(variables, keys, 0, new HashMap<>(), result);
        return result;
    }

    private void generateCombinations(Map<String, List<Object>> variables, List<String> keys, int index, 
                                      Map<String, Object> current, List<Map<String, Object>> result) {
        if (index == keys.size()) {
            result.add(new HashMap<>(current));
            return;
        }

        String key = keys.get(index);
        for (Object value : variables.get(key)) {
            current.put(key, value);
            generateCombinations(variables, keys, index + 1, current, result);
        }
    }
}
