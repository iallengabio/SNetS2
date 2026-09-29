package com.snets2.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Regression tests for CR-09 (fail-fast configuration validation). */
class ConfigValidatorTest {

    private static final String VALID = """
        {
          "networkTopology": {
            "nodes": [ {"id": "0", "tx": 10, "rx": 10, "regenerators": 0}, {"id": "1", "tx": 10, "rx": 10, "regenerators": 0} ],
            "links": [ {"source": "0", "destination": "1", "length": 100.0}, {"source": "1", "destination": "0", "length": 100.0} ],
            "cores": [ {"id": 0, "adjacentCores": [1]}, {"id": 1, "adjacentCores": [0]} ],
            "modulations": [ {"name": "4QAM", "maxRange": 5000.0, "M": 4.0, "SNR": 8.95, "XT": -19.03} ]
          },
          "physicalLayer": { "activeQoT": false, "guardBand": 1, "bvtSpectralWidth": 12.5E9, "spanLength": 80.0 },
          "simulation": { "requests": 100, "totalSlots": 32, "routing": "djk", "spectrumAssignment": "firstfit",
                          "coreAndSpectrumAssignment": "firstfitcore", "integratedRMSCA": "standard" },
          "traffic": { "load": 10.0, "bitRates": [ {"value": 100.0, "weight": 1.0} ] },
          "experimentalPlanning": { "replications": 1 }
        }
        """;

    private static ScenarioSetup setup(String json) throws Exception {
        return ConfigLoader.load(json).getBaseScenario();
    }

    private static String invalidMessage(String json) throws Exception {
        ScenarioSetup s = setup(json);
        return assertThrows(IllegalArgumentException.class, () -> ConfigValidator.validate(s)).getMessage();
    }

    @Test
    @DisplayName("A minimal valid configuration passes without warnings")
    void validConfiguration() throws Exception {
        assertEquals(List.of(), ConfigValidator.validate(setup(VALID)).warnings());
    }

    @Test
    @DisplayName("Missing load, loadByPair and a single node are errors (no silent defaults, no infinite loop)")
    void semanticErrors() throws Exception {
        assertTrue(invalidMessage(VALID.replace("\"load\": 10.0,", "")).contains("traffic.load"));
        assertTrue(invalidMessage(VALID.replace("\"load\": 10.0,", "\"load\": 10.0, \"loadByPair\": {\"0-1\": 1.0},"))
                .contains("loadByPair"));
        String oneNode = VALID.replace(", {\"id\": \"1\", \"tx\": 10, \"rx\": 10, \"regenerators\": 0}", "");
        String msg = invalidMessage(oneNode);
        assertTrue(msg.contains("at least 2 nodes") && msg.contains("unknown node"), msg);
    }

    @Test
    @DisplayName("Asymmetric or unknown core adjacency is an error")
    void coreAdjacency() throws Exception {
        assertTrue(invalidMessage(VALID.replace("{\"id\": 1, \"adjacentCores\": [0]}", "{\"id\": 1, \"adjacentCores\": []}"))
                .contains("symmetric"));
        assertTrue(invalidMessage(VALID.replace("{\"id\": 1, \"adjacentCores\": [0]}", "{\"id\": 1, \"adjacentCores\": [0, 7]}"))
                .contains("unknown adjacent core 7"));
    }

    @Test
    @DisplayName("Warm-up must be smaller than the number of requests; algorithms are required")
    void simulationErrors() throws Exception {
        assertTrue(invalidMessage(VALID.replace("\"requests\": 100,", "\"requests\": 100, \"warmUpRequests\": 100,"))
                .contains("warmUpRequests"));
        assertTrue(invalidMessage(VALID.replace("\"routing\": \"djk\",", "")).contains("simulation.routing"));
    }

    @Test
    @DisplayName("Ignored keys with non-default values and unknown metrics produce warnings")
    void warnings() throws Exception {
        String json = VALID
                .replace("\"routing\": \"djk\",", "\"routing\": \"djk\", \"kRouting\": \"newksp\", \"activeMetrics\": {\"GroomingStatistics\": true},")
                .replace("\"spanLength\": 80.0", "\"spanLength\": 80.0, \"switchInsertionLoss\": 5.0");
        List<String> w = ConfigValidator.validate(setup(json)).warnings();
        assertEquals(2, w.size(), w.toString());
        assertTrue(w.stream().anyMatch(s -> s.contains("kRouting")));
        assertTrue(w.stream().noneMatch(s -> s.contains("switchInsertionLoss")), "switchInsertionLoss is used (booster gain)");
        assertTrue(w.stream().anyMatch(s -> s.contains("GroomingStatistics")));
    }

    @Test
    @DisplayName("The experiments shipped with the repository are valid")
    void shippedExperimentsAreValid() throws Exception {
        for (String dir : List.of("experiment01", "experiment_only_blocking", "experiment_all_metrics")) {
            ExperimentSetup s = ConfigLoader.load(new File("experiments/" + dir + "/setup.json"));
            assertDoesNotThrow(() -> ConfigValidator.validate(s.getBaseScenario()), dir);
        }
    }

    @Test
    @DisplayName("Amplifier gain type and fixed-PSD reference bandwidth are validated")
    void amplifierAndPsdKeys() throws Exception {
        String base = "\"activeQoT\": false, \"guardBand\": 1";
        assertTrue(invalidMessage(VALID.replace(base, base + ", \"typeOfAmplifierGain\": 2"))
                .contains("typeOfAmplifierGain"));
        assertTrue(invalidMessage(VALID.replace(base, base + ", \"fixedPowerSpectralDensity\": true"))
                .contains("referenceBandwidthForPowerSpectralDensity"));
        assertEquals(List.of(), ConfigValidator.validate(setup(VALID.replace(base, base
                + ", \"typeOfAmplifierGain\": 1, \"fixedPowerSpectralDensity\": true, \"referenceBandwidthForPowerSpectralDensity\": 1.25E10"))).warnings());
    }

    @Test
    @DisplayName("Keys removed from SNetS v1 are rejected by the parser")
    void removedV1Keys() {
        for (String key : List.of("physicalLayerModel", "crosstalkModel", "typeOfTestQoT")) {
            String json = VALID.replace("\"activeQoT\": false", "\"activeQoT\": false, \"" + key + "\": 0");
            assertThrows(Exception.class, () -> ConfigLoader.load(json), key);
        }
    }
}
