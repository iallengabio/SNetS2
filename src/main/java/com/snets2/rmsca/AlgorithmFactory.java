package com.snets2.rmsca;

import java.util.HashMap;
import java.util.Map;

/**
 * Factory to dynamically instantiate RMSCA algorithms using their identifiers from JSON.
 */
public class AlgorithmFactory {
    private static final Map<String, Class<? extends IRMSCA>> registry = new HashMap<>();

    static {
        registry.put("integrated", StandardIntegratedRMSCA.class);
    }

    /**
     * Registers a new algorithm in the factory.
     *
     * @param id    The string ID used in the JSON configuration.
     * @param clazz The class to instantiate.
     */
    public static void register(String id, Class<? extends IRMSCA> clazz) {
        registry.put(id, clazz);
    }

    /**
     * Creates an instance of the algorithm specified by ID.
     *
     * @param id The algorithm ID.
     * @return An instance of {@link IRMSCA}.
     * @throws RuntimeException if the ID is not registered or instantiation fails.
     */
    public static IRMSCA create(String id) {
        Class<? extends IRMSCA> clazz = registry.get(id);
        if (clazz == null) {
            throw new RuntimeException("Algorithm not found in registry: " + id);
        }
        try {
            return clazz.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new RuntimeException("Failed to instantiate algorithm: " + id, e);
        }
    }
}
