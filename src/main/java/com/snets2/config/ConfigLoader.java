package com.snets2.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.IOException;
import java.util.Map;

/**
 * Utility to load the ExperimentSetup from a JSON file and manage dynamic scenario generation.
 */
public class ConfigLoader {
    private static final ObjectMapper mapper = new ObjectMapper();

    /**
     * Parses a JSON file into an ExperimentSetup object.
     *
     * @param jsonFile The configuration file.
     * @return The deserialized configuration.
     * @throws IOException If the file cannot be read or parsed.
     */
    public static ExperimentSetup load(File jsonFile) throws IOException {
        return mapper.readValue(jsonFile, ExperimentSetup.class);
    }

    /**
     * Parses a JSON string into an ExperimentSetup object.
     *
     * @param jsonContent The raw JSON string.
     * @return The deserialized configuration.
     * @throws IOException If the content is invalid.
     */
    public static ExperimentSetup load(String jsonContent) throws IOException {
        return mapper.readValue(jsonContent, ExperimentSetup.class);
    }

    /**
     * Applies a map of dot-notation overrides to a base scenario configuration and 
     * returns a new ScenarioSetup object.
     * This enables completely dynamic parameter sweeps without hardcoded logic.
     *
     * @param base      The base ScenarioSetup.
     * @param overrides A map of overrides (e.g., {"traffic.load": 150.0, "simulation.routing": "ksp"}).
     * @return A new ScenarioSetup with the overrides applied.
     */
    public static ScenarioSetup applyOverrides(ScenarioSetup base, Map<String, Object> overrides) {
        // 1. Convert the immutable base scenario into a mutable Map tree
        Map<String, Object> mapTree = mapper.convertValue(base, new TypeReference<Map<String, Object>>() {});

        // 2. Apply each override by navigating the dot notation
        for (Map.Entry<String, Object> entry : overrides.entrySet()) {
            String[] parts = entry.getKey().split("\\.");
            Map<String, Object> currentMap = mapTree;
            
            // Navigate to the parent object of the leaf property
            for (int i = 0; i < parts.length - 1; i++) {
                Object next = currentMap.get(parts[i]);
                if (!(next instanceof Map)) {
                    throw new IllegalArgumentException("Invalid override key path: " + entry.getKey() + " (not a map at '" + parts[i] + "')");
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> nextMap = (Map<String, Object>) next;
                currentMap = nextMap;
            }
            
            // Replace the leaf value
            currentMap.put(parts[parts.length - 1], entry.getValue());
        }

        // 3. Convert the modified Map tree back into a strongly-typed ScenarioSetup
        return mapper.convertValue(mapTree, ScenarioSetup.class);
    }
}
