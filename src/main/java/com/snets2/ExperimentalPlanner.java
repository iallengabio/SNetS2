package com.snets2;

import com.snets2.config.ConfigLoader;
import com.snets2.config.ExperimentSetup;
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

import java.io.File;
import java.nio.file.Paths;
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

        for (Map<String, Object> scenario : scenarios) {
            runScenario(scenario, replications);
        }

        // Export Results
        System.out.println("\nExporting results to Excel...");
        ExcelExporter exporter = new ExcelExporter();
        File outputFile = new File(experimentFolder, "results.xlsx");
        exporter.export(aggregatedResult, outputFile.toPath());
        System.out.println("Results saved to: " + outputFile.getAbsolutePath());
    }

    private void runScenario(Map<String, Object> scenario, int replications) throws Exception {
        System.out.println("\n--- SCENARIO: " + scenario + " ---");
        
        for (int rep = 0; rep < replications; rep++) {
            runReplication(rep, scenario);
        }
    }

    private void runReplication(int repId, Map<String, Object> scenario) throws Exception {
        System.out.print(String.format("  > Rep %d... ", repId));
        
        // 1. Map Topology
        NetworkTopology topology = TopologyMapper.map(
            baseSetup.networkTopology(), 
            baseSetup.simulation().totalSlots()
        );

        IRMSCA rmsca = AlgorithmFactory.create(baseSetup.simulation().integratedRmlsa());

        // 2. Apply Scenario Overrides (Parameter Sweep)
        double load = baseSetup.traffic().load() != null ? baseSetup.traffic().load() : 1.0;
        if (scenario.containsKey("traffic.load")) {
            load = Double.parseDouble(scenario.get("traffic.load").toString());
        }

        if (rmsca instanceof com.snets2.rmsca.StandardIntegratedRMSCA integrated) {
            String spectrumAlgo = baseSetup.simulation().spectrumAssignment();
            if (scenario.containsKey("simulation.spectrumAssignment")) {
                spectrumAlgo = scenario.get("simulation.spectrumAssignment").toString();
            }

            if (spectrumAlgo.equalsIgnoreCase("randomfit")) {
                integrated.setSpectrumAssignment(new com.snets2.rmsca.RandomFitSpectrumAssignment());
            } else if (spectrumAlgo.equalsIgnoreCase("dummyfit")) {
                integrated.setSpectrumAssignment(new com.snets2.rmsca.DummyFitSpectrumAssignment());
            } else {
                integrated.setSpectrumAssignment(new com.snets2.rmsca.FirstFitSpectrumAssignment());
            }
        }

        // 3. Initialize Control Plane
        ControlPlane cp = new ControlPlane(
            topology, 
            rmsca, 
            baseSetup.physicalLayer().bvtSpectralWidth()
        );

        // 3. Initialize Engine
        SimulationEngine engine = new SimulationEngine(
            topology, 
            cp, 
            baseSetup.simulation().requests(), 
            load, 
            baseSetup.traffic().bitRates(),
            repId // Seed
        );

        // 4. Seed First Event
        List<Node> nodes = topology.nodes();
        Node src = nodes.get(engine.getRandom().nextInt(nodes.size()));
        Node dest;
        do {
            dest = nodes.get(engine.getRandom().nextInt(nodes.size()));
        } while (src == dest);
        
        double firstBitRate = engine.nextBitRate();
        engine.schedule(new ArrivalEvent(0.0, src, dest, firstBitRate));

        // 5. Schedule periodic observations (organizational rule)
        engine.schedule(new ResourceUtilizationObservationEvent(0.0));

        // 6. Run
        engine.run();

        // 7. Collect results into the aggregator
        engine.getMetricsManager().getBitRateBlocking().fillResults(aggregatedResult, scenario, repId);
        engine.getMetricsManager().getResourceUtilization().fillResults(aggregatedResult, scenario, repId);

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
