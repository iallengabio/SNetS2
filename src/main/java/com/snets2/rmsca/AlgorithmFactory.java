package com.snets2.rmsca;

import com.snets2.rmsca.core.*;
import com.snets2.rmsca.modulation.*;
import com.snets2.rmsca.routing.*;
import com.snets2.rmsca.spectrum.*;
import java.util.HashMap;
import java.util.Map;

/**
 * Factory to dynamically instantiate RMSCA algorithms and their sub-components.
 */
public class AlgorithmFactory {
    private static final Map<String, Class<? extends IRMSCA>> integratedRegistry = new HashMap<>();
    private static final Map<String, Class<? extends IRouting>> routingRegistry = new HashMap<>();
    private static final Map<String, Class<? extends IModulationSelection>> modulationRegistry = new HashMap<>();
    private static final Map<String, Class<? extends ICoreAssignment>> coreRegistry = new HashMap<>();
    private static final Map<String, Class<? extends ISpectrumAssignment>> spectrumRegistry = new HashMap<>();
    private static final Map<String, Class<? extends com.snets2.rmsca.regenerator.IRegeneratorAssignment>> regeneratorRegistry = new HashMap<>();

    static {
        // Integrated
        integratedRegistry.put("standard", StandardIntegratedRMSCA.class);
        
        // Routing
        routingRegistry.put("djk", DijkstraRouting.class);
        routingRegistry.put("ksp", KShortestPathsRouting.class);
        routingRegistry.put("newksp", KShortestPathsRouting.class);
        
        // Modulation
        modulationRegistry.put("fixed", FixedModulationSelection.class);
        modulationRegistry.put("distance-adaptive", DistanceAdaptiveModulationSelection.class);
        
        // Core
        coreRegistry.put("firstfitcore", FirstFitCoreAssignment.class);
        coreRegistry.put("randomfitcore", RandomFitCoreAssignment.class);
        coreRegistry.put("mincrosstalkcore", MinCrosstalkCoreAssignment.class);
        coreRegistry.put("mincrosstalk", MinCrosstalkCoreAssignment.class);
        
        // Spectrum
        spectrumRegistry.put("firstfit", FirstFitSpectrumAssignment.class);
        spectrumRegistry.put("randomfit", RandomFitSpectrumAssignment.class);
        spectrumRegistry.put("dummyfit", DummyFitSpectrumAssignment.class);
        spectrumRegistry.put("lastfit", LastFitSpectrumAssignment.class);
        spectrumRegistry.put("lf", LastFitSpectrumAssignment.class);
        spectrumRegistry.put("exactfit", ExactFitSpectrumAssignment.class);
        spectrumRegistry.put("ef", ExactFitSpectrumAssignment.class);
        
        // Regenerator
        regeneratorRegistry.put("aar", com.snets2.rmsca.regenerator.AsSoonAsRequiredRegeneratorAssignment.class);
    }

    public static IRMSCA createIntegrated(String id) {
        return createInstance(id, integratedRegistry, "Integrated Algorithm");
    }

    public static IRouting createRouting(String id) {
        return createInstance(id, routingRegistry, "Routing Algorithm");
    }

    public static IModulationSelection createModulation(String id) {
        return createInstance(id, modulationRegistry, "Modulation Selection");
    }

    public static ICoreAssignment createCore(String id) {
        return createInstance(id, coreRegistry, "Core Assignment");
    }

    public static ISpectrumAssignment createSpectrum(String id) {
        return createInstance(id, spectrumRegistry, "Spectrum Assignment");
    }

    public static com.snets2.rmsca.regenerator.IRegeneratorAssignment createRegenerator(String id) {
        if (id == null || id.isEmpty()) return null;
        return createInstance(id, regeneratorRegistry, "Regenerator Assignment");
    }

    private static <T> T createInstance(String id, Map<String, Class<? extends T>> registry, String type) {
        Class<? extends T> clazz = registry.get(id.toLowerCase());
        if (clazz == null) {
            throw new RuntimeException(type + " not found in registry: " + id);
        }
        try {
            return clazz.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new RuntimeException("Failed to instantiate " + type + ": " + id, e);
        }
    }
}
