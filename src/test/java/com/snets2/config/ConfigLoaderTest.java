package com.snets2.config;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;

class ConfigLoaderTest {

    @Test
    void testLoadSampleJson() throws IOException {
        String json = """
        {
          "networkTopology": {
            "nodes": [{"id": "0", "tx": 10, "rx": 10, "regenerators": 2}],
            "links": [{"source": "0", "destination": "1", "length": 100.0}],
            "cores": [{"id": 0, "adjacentCores": [1]}]
          },
          "physicalLayer": {
             "activeQoT": true,
             "guardBand": 1,
             "bvtSpectralWidth": 12.5E9
          },
          "simulation": {
            "requests": 1000,
            "routing": "djk",
            "activeMetrics": {"BlockingProbability": true}
          },
          "traffic": {
            "loadDistributionPerPair": "uniform",
            "load": 100.0
          },
          "experimentalPlanning": {
            "replications": 5,
            "traffic.load": [100, 200, 300]
          }
        }
        """;

        ExperimentSetup setup = ConfigLoader.load(json);

        assertNotNull(setup);
        assertEquals(1, setup.networkTopology().nodes().size());
        assertTrue(setup.physicalLayer().activeQoT());
        assertEquals(1000, setup.simulation().requests());
        assertEquals(100.0, setup.traffic().load());
        assertEquals(5, setup.experimentalPlanning().getReplications());
        
        // Check dynamic variables
        assertTrue(setup.experimentalPlanning().getVariables().containsKey("traffic.load"));
        assertEquals(3, setup.experimentalPlanning().getVariables().get("traffic.load").size());
    }
}
