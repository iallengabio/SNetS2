package com.snets2.config;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Configures the parameter sweep and replications for an experiment.
 */
public class ExperimentalPlanningConfig {
    private int replications;
    private final Map<String, List<Object>> variables = new HashMap<>();

    public int getReplications() {
        return replications;
    }

    public void setReplications(int replications) {
        this.replications = replications;
    }

    @JsonAnySetter
    public void addVariable(String key, List<Object> values) {
        if (!key.equals("replications")) {
            variables.put(key, values);
        }
    }

    public Map<String, List<Object>> getVariables() {
        return variables;
    }
}
