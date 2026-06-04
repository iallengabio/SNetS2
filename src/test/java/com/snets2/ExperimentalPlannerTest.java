package com.snets2;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

class ExperimentalPlannerTest {

    @Test
    void testMultithreadedExecutionAndCleanup(@TempDir Path tempDir) throws Exception {
        // Create setup.json
        String json = """
        {
          "networkTopology": {
            "nodes": [
              {"id": "0", "tx": 10, "rx": 10, "regenerators": 2},
              {"id": "1", "tx": 10, "rx": 10, "regenerators": 2}
            ],
            "links": [
              {"source": "0", "destination": "1", "length": 100.0},
              {"source": "1", "destination": "0", "length": 100.0}
            ],
            "cores": [
              {"id": 0, "adjacentCores": []}
            ],
            "modulations": [
              {"name": "4QAM", "maxRange": 5000.0, "M": 4.0, "SNR": 8.95, "XT": -19.03}
            ]
          },
          "physicalLayer": {
             "activeQoT": false,
             "guardBand": 1,
             "bvtSpectralWidth": 12.5E9,
             "spanLength": 80.0
          },
          "simulation": {
            "requests": 10,
            "totalSlots": 320,
            "routing": "djk",
            "spectrumAssignment": "firstfit",
            "coreAndSpectrumAssignment": "firstfitcore",
            "integratedRMSCA": "standard",
            "modulationSelection": "fixed",
            "activeMetrics": {"BlockingProbability": true}
          },
          "traffic": {
            "loadDistributionPerPair": "uniform",
            "load": 10.0,
            "bitRates": [{"value": 100.0, "weight": 1.0}]
          },
          "experimentalPlanning": {
            "replications": 2,
            "traffic.load": [10.0, 20.0]
          }
        }
        """;

        File setupFile = tempDir.resolve("setup.json").toFile();
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(setupFile))) {
            writer.write(json);
        }

        ExperimentalPlanner planner = new ExperimentalPlanner(tempDir.toFile(), 2);
        planner.run();

        // Check if results.xlsx exists
        File resultsFile = tempDir.resolve("results.xlsx").toFile();
        assertTrue(resultsFile.exists(), "results.xlsx should be created");

        // Check if progress.jsonl has been cleaned up
        File progressFile = tempDir.resolve("progress.jsonl").toFile();
        assertFalse(progressFile.exists(), "progress.jsonl should be deleted upon successful completion");
    }

    @Test
    void testResumeFromCheckpoint(@TempDir Path tempDir) throws Exception {
        // Create setup.json
        String json = """
        {
          "networkTopology": {
            "nodes": [
              {"id": "0", "tx": 10, "rx": 10, "regenerators": 2},
              {"id": "1", "tx": 10, "rx": 10, "regenerators": 2}
            ],
            "links": [
              {"source": "0", "destination": "1", "length": 100.0},
              {"source": "1", "destination": "0", "length": 100.0}
            ],
            "cores": [
              {"id": 0, "adjacentCores": []}
            ],
            "modulations": [
              {"name": "4QAM", "maxRange": 5000.0, "M": 4.0, "SNR": 8.95, "XT": -19.03}
            ]
          },
          "physicalLayer": {
             "activeQoT": false,
             "guardBand": 1,
             "bvtSpectralWidth": 12.5E9,
             "spanLength": 80.0
          },
          "simulation": {
            "requests": 10,
            "totalSlots": 320,
            "routing": "djk",
            "spectrumAssignment": "firstfit",
            "coreAndSpectrumAssignment": "firstfitcore",
            "integratedRMSCA": "standard",
            "modulationSelection": "fixed",
            "activeMetrics": {"BlockingProbability": true}
          },
          "traffic": {
            "loadDistributionPerPair": "uniform",
            "load": 10.0,
            "bitRates": [{"value": 100.0, "weight": 1.0}]
          },
          "experimentalPlanning": {
            "replications": 2,
            "traffic.load": [10.0]
          }
        }
        """;

        File setupFile = tempDir.resolve("setup.json").toFile();
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(setupFile))) {
            writer.write(json);
        }

        // Mock a progress record for repId = 0
        String mockProgressLine = """
        {"scenario":{"traffic.load":10.0},"repId":0,"metrics":[{"sheet":"BlockingProbability","subMetric":"General","dimensions":{},"value":0.5}]}
        """;

        File progressFile = tempDir.resolve("progress.jsonl").toFile();
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(progressFile))) {
            writer.write(mockProgressLine);
            writer.newLine();
        }

        // We run with 2 threads
        ExperimentalPlanner planner = new ExperimentalPlanner(tempDir.toFile(), 2);
        planner.run();

        // If it successfully resumed, it only ran repId = 1, and the final results contains both
        File resultsFile = tempDir.resolve("results.xlsx").toFile();
        assertTrue(resultsFile.exists(), "results.xlsx should be created");
        assertFalse(progressFile.exists(), "progress.jsonl should be deleted after completion");
    }
}
