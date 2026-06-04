package com.snets2;

import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Orchestrates the execution of a complete experiment, including 
 * parameter sweeps and multiple replications. Supports multithreading and 
 * checkpointing/recovery.
 */
public class ExperimentalPlanner {
    private final File experimentFolder;
    private final int numThreads;
    private ExperimentSetup baseSetup;
    private SimulationResult aggregatedResult;
    private final AtomicInteger completedCounter = new AtomicInteger(0);
    private int totalTasks;

    private static final ObjectMapper mapper = new ObjectMapper();

    public ExperimentalPlanner(File folder) {
        this(folder, 1);
    }

    public ExperimentalPlanner(File folder, int numThreads) {
        this.experimentFolder = folder;
        this.numThreads = numThreads;
    }

    public void run() throws Exception {
        File setupFile = new File(experimentFolder, "setup.json");
        this.baseSetup = ConfigLoader.load(setupFile);

        System.out.println("Starting Experimental Planning for: " + experimentFolder.getName());
        
        Map<String, List<Object>> sweepVariables = baseSetup.experimentalPlanning().getVariables();
        List<Map<String, Object>> scenarios = generateParameterSweep(sweepVariables);
        int replications = baseSetup.experimentalPlanning().getReplications();

        System.out.println("Generated " + scenarios.size() + " unique scenarios.");
        this.totalTasks = scenarios.size() * replications;
        this.aggregatedResult = new SimulationResult(replications);

        Set<String> completedReplications = ConcurrentHashMap.newKeySet();
        File progressFile = new File(experimentFolder, "progress.jsonl");

        if (progressFile.exists() && progressFile.isFile()) {
            System.out.println("Found progress file: " + progressFile.getName() + ". Resuming experiment...");
            try (BufferedReader reader = new BufferedReader(new FileReader(progressFile))) {
                String line;
                int resumeCount = 0;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    try {
                        ProgressRecord record = mapper.readValue(line, ProgressRecord.class);
                        // Add metrics back to aggregatedResult
                        for (MetricEntry entry : record.getMetrics()) {
                            aggregatedResult.addValue(
                                entry.getSheet(),
                                entry.getSubMetric(),
                                entry.getDimensions(),
                                record.getScenario(),
                                record.getRepId(),
                                entry.getValue()
                            );
                        }
                        String key = getReplicationKey(record.getScenario(), record.getRepId());
                        completedReplications.add(key);
                        resumeCount++;
                    } catch (Exception parseEx) {
                        System.err.println("WARNING: Failed to parse line from progress file: " + parseEx.getMessage());
                    }
                }
                System.out.println("Resumed " + resumeCount + " completed replications from checkpoint.");
                this.completedCounter.set(completedReplications.size());
                if (resumeCount > 0) {
                    double percent = (double) resumeCount * 100.0 / totalTasks;
                    System.out.println(String.format("Progress: %.2f%% (%d/%d replications)", percent, resumeCount, totalTasks));
                }
            } catch (Exception e) {
                System.err.println("WARNING: Failed to read progress file: " + e.getMessage() + ". Starting fresh or from partial progress.");
            }
        }

        ScenarioSetup baseScenario = baseSetup.getBaseScenario();
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);

        System.out.println("Running simulation sweep using " + numThreads + " thread(s)...");

        List<Throwable> exceptions = Collections.synchronizedList(new ArrayList<>());

        for (Map<String, Object> scenario : scenarios) {
            ScenarioSetup scenarioSetup = ConfigLoader.applyOverrides(baseScenario, scenario);
            for (int rep = 0; rep < replications; rep++) {
                final int repId = rep;
                final Map<String, Object> scenarioMap = scenario;
                final ScenarioSetup setup = scenarioSetup;

                String key = getReplicationKey(scenarioMap, repId);
                if (completedReplications.contains(key)) {
                    continue; // Skip already completed replication
                }

                executor.submit(() -> {
                    try {
                        runReplicationAndSaveProgress(repId, setup, scenarioMap, progressFile);
                    } catch (Throwable t) {
                        exceptions.add(t);
                        System.err.println("Error executing replication " + repId + " for scenario " + scenarioMap + ": " + t.getMessage());
                        t.printStackTrace();
                    }
                });
            }
        }

        executor.shutdown();
        try {
            if (!executor.awaitTermination(7, TimeUnit.DAYS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
            throw new RuntimeException("Simulation execution was interrupted", e);
        }

        if (!exceptions.isEmpty()) {
            throw new RuntimeException("One or more replications failed. Check logs for details. Total failures: " + exceptions.size());
        }

        // Export Results
        System.out.println("\nExporting results to Excel...");
        ExcelExporter exporter = new ExcelExporter();
        File outputFile = new File(experimentFolder, "results.xlsx");
        exporter.export(aggregatedResult, outputFile.toPath());
        System.out.println("Results saved to: " + outputFile.getAbsolutePath());

        // Delete progress file on successful run completion
        if (progressFile.exists()) {
            progressFile.delete();
        }
    }

    private void runReplicationAndSaveProgress(int repId, ScenarioSetup setup, Map<String, Object> scenarioMap, File progressFile) throws Exception {
        int totalReps = baseSetup.experimentalPlanning().getReplications();
        SimulationResult repResult = new SimulationResult(totalReps);

        // 1. Map Topology
        NetworkTopology topology = TopologyMapper.map(
            setup.networkTopology(), 
            setup.physicalLayer(),
            setup.simulation().totalSlots()
        );

        // 2. Instantiate Algorithm Chain
        IRMSCA rmsca = AlgorithmFactory.createIntegrated(setup.simulation().integratedRMSCA());

        if (rmsca instanceof StandardIntegratedRMSCA standard) {
            standard.setRouting(AlgorithmFactory.createRouting(setup.simulation().routing()));
            standard.setCoreAssignment(AlgorithmFactory.createCore(setup.simulation().coreAndSpectrumAssignment()));
            standard.setSpectrumAssignment(AlgorithmFactory.createSpectrum(setup.simulation().spectrumAssignment()));
        }

        // 3. Initialize Control Plane
        ControlPlane cp = new ControlPlane(
            topology, 
            rmsca, 
            setup.physicalLayer().bvtSpectralWidth(),
            setup.physicalLayer().guardBand(),
            setup.physicalLayer()
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

        // 8. Collect results locally
        engine.getMetricsManager().getBitRateBlocking().fillResults(repResult, scenarioMap, repId);
        engine.getMetricsManager().getResourceUtilization().fillResults(repResult, scenarioMap, repId);
        engine.getMetricsManager().getPhysicalLayer().fillResults(repResult, scenarioMap, repId);
        engine.getMetricsManager().getExternalFragmentation().fillResults(repResult, scenarioMap, repId);
        engine.getMetricsManager().getRelativeFragmentation().fillResults(repResult, scenarioMap, repId);
        engine.getMetricsManager().getModulationUtilization().fillResults(repResult, scenarioMap, repId);
        engine.getMetricsManager().getSpectrumSize().fillResults(repResult, scenarioMap, repId);
        engine.getMetricsManager().getTransmittersReceiversRegeneratorsUtilization().fillResults(repResult, scenarioMap, repId);
        
        if (engine.getMetricsManager().getConsumedEnergy() != null) {
            engine.getMetricsManager().getConsumedEnergy().fillResults(repResult, scenarioMap, repId, engine.getCurrentTime());
        }

        // 9. Accumulate into the central aggregatedResult and prepare the progress record
        List<MetricEntry> entries = new ArrayList<>();
        for (Map.Entry<String, Map<String, SimulationResult.MetricRow>> sheetEntry : repResult.getData().entrySet()) {
            String sheet = sheetEntry.getKey();
            for (SimulationResult.MetricRow row : sheetEntry.getValue().values()) {
                Double val = row.getRepValues().get(repId);
                if (val != null) {
                    aggregatedResult.addValue(sheet, row.getSubMetric(), row.getDimensions(), scenarioMap, repId, val);
                    
                    MetricEntry entry = new MetricEntry();
                    entry.setSheet(sheet);
                    entry.setSubMetric(row.getSubMetric());
                    entry.setDimensions(row.getDimensions());
                    entry.setValue(val);
                    entries.add(entry);
                }
            }
        }

        // 10. Write progress record to the progress log file in a thread-safe manner
        ProgressRecord record = new ProgressRecord();
        record.setScenario(scenarioMap);
        record.setRepId(repId);
        record.setMetrics(entries);

        String jsonLine = mapper.writeValueAsString(record);

        synchronized (progressFile) {
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(progressFile, true))) {
                writer.write(jsonLine);
                writer.newLine();
            }
        }

        int current = completedCounter.incrementAndGet();
        double percent = (double) current * 100.0 / totalTasks;
        System.out.println(String.format("Progress: %.2f%% (%d/%d replications)", percent, current, totalTasks));

        if (SimulationConstants.debugEnabled) {
            double bp = engine.getMetricsManager().getBitRateBlocking().getGeneralBlockingProbability();
            String logMsg = String.format("[Thread %s] Scenario %s | Rep %d completed. BP: %.4e",
                Thread.currentThread().getName(), scenarioMap, repId, bp);
            System.out.println(logMsg);
        }
    }

    /**
     * Generates a Cartesian product of all variables for the parameter sweep.
     * Iterates with sorted keys to ensure deterministic scenario ordering.
     */
    private List<Map<String, Object>> generateParameterSweep(Map<String, List<Object>> variables) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (variables.isEmpty()) {
            result.add(new HashMap<>());
            return result;
        }

        List<String> keys = new ArrayList<>(variables.keySet());
        Collections.sort(keys); // Ensure deterministic combination order
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

    private static String getReplicationKey(Map<String, Object> scenarioMap, int repId) {
        try {
            TreeMap<String, Object> sortedMap = new TreeMap<>(scenarioMap);
            String canonicalJson = mapper.writeValueAsString(sortedMap);
            return canonicalJson + ":::" + repId;
        } catch (Exception e) {
            throw new RuntimeException("Error computing replication key", e);
        }
    }

    // Classes nested representing progress mapping
    public static class ProgressRecord {
        private Map<String, Object> scenario;
        private int repId;
        private List<MetricEntry> metrics;

        public Map<String, Object> getScenario() { return scenario; }
        public void setScenario(Map<String, Object> scenario) { this.scenario = scenario; }
        public int getRepId() { return repId; }
        public void setRepId(int repId) { this.repId = repId; }
        public List<MetricEntry> getMetrics() { return metrics; }
        public void setMetrics(List<MetricEntry> metrics) { this.metrics = metrics; }
    }

    public static class MetricEntry {
        private String sheet;
        private String subMetric;
        private Map<String, String> dimensions;
        private double value;

        public String getSheet() { return sheet; }
        public void setSheet(String sheet) { this.sheet = sheet; }
        public String getSubMetric() { return subMetric; }
        public void setSubMetric(String subMetric) { this.subMetric = subMetric; }
        public Map<String, String> getDimensions() { return dimensions; }
        public void setDimensions(Map<String, String> dimensions) { this.dimensions = dimensions; }
        public double getValue() { return value; }
        public void setValue(double value) { this.value = value; }
    }
}
