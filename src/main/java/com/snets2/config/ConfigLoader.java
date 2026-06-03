package com.snets2.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.IOException;

/**
 * Utility to load the ExperimentSetup from a JSON file.
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
}
