package com.snets2.rmsca;

import java.util.Map;
import java.util.Set;

/**
 * An RMSCA algorithm (integrated or sub-algorithm) with numeric parameters read from
 * {@code simulation.algorithmParameters} (e.g. {@code "sigma": 2.0}).
 *
 * <p>The parameters share one flat namespace, as the {@code variables} of SNetS v1. Each algorithm reads only the
 * names it declares; {@link com.snets2.config.ConfigValidator} warns about names that no configured algorithm reads.</p>
 */
public interface Configurable {

    /** Names of the parameters this algorithm reads. */
    Set<String> parameterNames();

    /**
     * Reads the parameters; absent names keep their defaults.
     *
     * @throws IllegalArgumentException if a value is not a number or is out of range
     */
    void configure(Map<String, Object> parameters);

    /** Numeric value of {@code name}, or {@code defaultValue} if absent. Accepts JSON numbers and numeric strings. */
    static double doubleParameter(Map<String, Object> parameters, String name, double defaultValue) {
        Object value = parameters.get(name);
        if (value == null) return defaultValue;
        if (value instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(value.toString().trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("simulation.algorithmParameters." + name + " must be a number, got \"" + value + "\"");
        }
    }
}
