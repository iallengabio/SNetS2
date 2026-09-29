package com.snets2.config;

import java.util.*;

/**
 * Fail-fast validation of a {@link ScenarioSetup} before any replication runs.
 *
 * <p><b>Errors</b> (inconsistent or unsupported configuration) abort the experiment with a single
 * {@link IllegalArgumentException} listing all problems. <b>Warnings</b> report keys that are accepted
 * by the schema but not used by the simulator when they carry a non-default value, and unknown metric
 * names, so that a setting never silently has no effect.</p>
 */
public final class ConfigValidator {

    /** Metric names understood by {@code activeMetrics}. Omitted metrics are enabled by default. */
    public static final Set<String> KNOWN_METRICS = Set.of(
        "BlockingProbability", "BitRateBlockingProbability", "SpectrumUtilization", "SpectrumSizeStatistics",
        "ExternalFragmentation", "RelativeFragmentation", "TransmittersReceiversRegeneratorsUtilization",
        "ModulationUtilization", "ConsumedEnergy", "CrosstalkStatistics", "SimulationMetadata");

    private ConfigValidator() {}

    /** Validation outcome: warnings only (errors are thrown). */
    public record Result(List<String> warnings) {}

    /**
     * Validates the scenario.
     *
     * @throws IllegalArgumentException if the configuration has at least one error.
     */
    public static Result validate(ScenarioSetup setup) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (setup.networkTopology() == null) errors.add("networkTopology is required");
        else validateTopology(setup.networkTopology(), errors);

        if (setup.physicalLayer() == null) errors.add("physicalLayer is required");
        else validatePhysicalLayer(setup.physicalLayer(), errors, warnings);

        if (setup.simulation() == null) errors.add("simulation is required");
        else validateSimulation(setup.simulation(), errors, warnings);

        if (setup.traffic() == null) errors.add("traffic is required");
        else validateTraffic(setup.traffic(), errors);

        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("Invalid configuration:\n - " + String.join("\n - ", errors));
        }
        return new Result(warnings);
    }

    private static void validateTopology(NetworkTopologyConfig t, List<String> errors) {
        Set<String> nodeIds = new HashSet<>();
        if (t.nodes() == null || t.nodes().size() < 2) {
            errors.add("networkTopology.nodes must contain at least 2 nodes");
        } else {
            for (NodeConfig n : t.nodes()) {
                if (n.id() == null || !nodeIds.add(n.id())) errors.add("duplicate or missing node id: " + n.id());
                if (n.tx() < 0 || n.rx() < 0 || n.regenerators() < 0) errors.add("node " + n.id() + ": tx/rx/regenerators must be >= 0");
            }
        }

        if (t.links() == null || t.links().isEmpty()) {
            errors.add("networkTopology.links must not be empty");
        } else {
            Set<String> linkKeys = new HashSet<>();
            for (LinkConfig l : t.links()) {
                String key = l.source() + "->" + l.destination();
                if (!nodeIds.contains(l.source()) || !nodeIds.contains(l.destination())) errors.add("link " + key + " references an unknown node");
                if (Objects.equals(l.source(), l.destination())) errors.add("link " + key + " is a self-loop");
                if (!(l.length() > 0)) errors.add("link " + key + ": length must be > 0 km");
                if (!linkKeys.add(key)) errors.add("duplicate link " + key + " (links are directed; declare each direction once)");
            }
        }

        if (t.cores() == null || t.cores().isEmpty()) {
            errors.add("networkTopology.cores must not be empty");
        } else {
            Map<Integer, List<Integer>> adjacency = new HashMap<>();
            for (CoreConfig c : t.cores()) {
                if (adjacency.put(c.id(), c.adjacentCores() == null ? List.of() : c.adjacentCores()) != null) {
                    errors.add("duplicate core id " + c.id());
                }
            }
            for (Map.Entry<Integer, List<Integer>> e : adjacency.entrySet()) {
                for (Integer adj : e.getValue()) {
                    if (adj.equals(e.getKey())) errors.add("core " + e.getKey() + " lists itself as adjacent");
                    else if (!adjacency.containsKey(adj)) errors.add("core " + e.getKey() + " lists unknown adjacent core " + adj);
                    else if (!adjacency.get(adj).contains(e.getKey())) {
                        errors.add("core adjacency must be symmetric: " + e.getKey() + " lists " + adj + " but not vice versa");
                    }
                }
            }
        }

        if (t.modulations() == null || t.modulations().isEmpty()) {
            errors.add("networkTopology.modulations must not be empty");
        } else {
            for (ModulationConfig m : t.modulations()) {
                if (!(m.M() >= 2)) errors.add("modulation " + m.name() + ": M must be >= 2");
                if (!(m.maxRange() > 0)) errors.add("modulation " + m.name() + ": maxRange must be > 0 km");
            }
        }
    }

    private static void validatePhysicalLayer(PhysicalLayerConfig p, List<String> errors, List<String> warnings) {
        if (!(p.bvtSpectralWidth() > 0)) errors.add("physicalLayer.bvtSpectralWidth (slot width, Hz) must be > 0");
        if (!(p.spanLength() > 0)) errors.add("physicalLayer.spanLength must be > 0 km");
        if (p.guardBand() < 0) errors.add("physicalLayer.guardBand must be >= 0");
        if (p.polarizationModes() < 0) errors.add("physicalLayer.polarizationModes must be >= 0 (0 = not configured = 1)");
        if (p.rateOfFEC() < 0) errors.add("physicalLayer.rateOfFEC (FEC overhead) must be >= 0");

        if (p.activeQoT()) {
            if (p.activeASE()) {
                if (!(p.fiberLoss() > 0)) errors.add("physicalLayer.fiberLoss must be > 0 dB/km when activeASE");
                if (!(p.constantOfPlanck() > 0) || !(p.amplificationFrequency() > 0)) {
                    errors.add("physicalLayer.constantOfPlanck and amplificationFrequency must be > 0 when activeASE");
                }
            }
            if (p.activeNLI()) {
                if (!(p.fiberLoss() > 0) || !(p.fiberDispersion() > 0) || !(p.centerFrequency() > 0) || p.fiberNonlinearity() < 0) {
                    errors.add("physicalLayer.fiberLoss, fiberDispersion, centerFrequency must be > 0 and fiberNonlinearity >= 0 when activeNLI");
                }
            }
            if (p.activeXT()) {
                if (!(p.propagationConstant() > 0) || !(p.corePitch() > 0)) {
                    errors.add("physicalLayer.propagationConstant and corePitch must be > 0 when activeXT");
                }
            }
        }

        // Accepted by the schema but not used by the simulator
        ignoredIfSet(warnings, "physicalLayer.physicalLayerModel", p.physicalLayerModel() != 0);
        ignoredIfSet(warnings, "physicalLayer.crosstalkModel", p.crosstalkModel() != 0);
        ignoredIfSet(warnings, "physicalLayer.typeOfTestQoT", p.typeOfTestQoT() != 0);
        ignoredIfSet(warnings, "physicalLayer.powerSaturationOfOpticalAmplifier", p.powerSaturationOfOpticalAmplifier() != 0);
        ignoredIfSet(warnings, "physicalLayer.noiseFactorModelParameterA1", p.noiseFactorModelParameterA1() != 0);
        ignoredIfSet(warnings, "physicalLayer.noiseFactorModelParameterA2", p.noiseFactorModelParameterA2() != 0);
        ignoredIfSet(warnings, "physicalLayer.typeOfAmplifierGain", p.typeOfAmplifierGain() != 0);
        ignoredIfSet(warnings, "physicalLayer.switchInsertionLoss", p.switchInsertionLoss() != 0);
        ignoredIfSet(warnings, "physicalLayer.fixedPowerSpectralDensity", p.fixedPowerSpectralDensity());
        ignoredIfSet(warnings, "physicalLayer.referenceBandwidthForPowerSpectralDensity", p.referenceBandwidthForPowerSpectralDensity() != 0);
    }

    private static void validateSimulation(SimulationConfig s, List<String> errors, List<String> warnings) {
        if (s.requests() <= 0) errors.add("simulation.requests must be > 0");
        if (s.warmUpRequests() < 0 || s.warmUpRequests() >= s.requests()) {
            errors.add("simulation.warmUpRequests must be >= 0 and < simulation.requests");
        }
        if (s.totalSlots() <= 0) errors.add("simulation.totalSlots must be > 0");
        if (isBlank(s.integratedRMSCA())) errors.add("simulation.integratedRMSCA is required (e.g. \"standard\")");
        if (isBlank(s.routing())) errors.add("simulation.routing is required (e.g. \"djk\")");
        if (isBlank(s.coreAndSpectrumAssignment())) errors.add("simulation.coreAndSpectrumAssignment is required (e.g. \"firstfitcore\")");
        if (isBlank(s.spectrumAssignment())) errors.add("simulation.spectrumAssignment is required (e.g. \"firstfit\")");
        if (s.threads() < 0) errors.add("simulation.threads must be >= 0");

        ignoredIfSet(warnings, "simulation.kRouting", !isBlank(s.kRouting()));
        ignoredIfSet(warnings, "simulation.grooming", !isBlank(s.grooming()));
        ignoredIfSet(warnings, "simulation.reallocation", !isBlank(s.reallocation()));
        ignoredIfSet(warnings, "simulation.powerAssignment", !isBlank(s.powerAssignment()));
        ignoredIfSet(warnings, "simulation.networkType", s.networkType() != 0);

        if (s.activeMetrics() != null) {
            for (String metric : s.activeMetrics().keySet()) {
                if (!KNOWN_METRICS.contains(metric)) {
                    warnings.add("simulation.activeMetrics." + metric + " is not a known metric and is ignored");
                }
            }
        }
    }

    private static void validateTraffic(TrafficConfig t, List<String> errors) {
        if (t.load() == null) errors.add("traffic.load (total offered load, Erlang) is required");
        else if (!(t.load() > 0)) errors.add("traffic.load must be > 0");
        if (t.loadByPair() != null) errors.add("traffic.loadByPair is not supported; use traffic.load");
        if (t.loadDistributionPerPair() != null && !t.loadDistributionPerPair().equalsIgnoreCase("uniform")) {
            errors.add("traffic.loadDistributionPerPair: only \"uniform\" is supported");
        }
        if (t.bitRates() != null) {
            double weightSum = 0;
            for (TrafficConfig.BitRateConfig br : t.bitRates()) {
                if (!(br.value() > 0)) errors.add("traffic.bitRates: value must be > 0 Gbps");
                if (br.weight() < 0) errors.add("traffic.bitRates: weight must be >= 0");
                weightSum += br.weight();
            }
            if (!t.bitRates().isEmpty() && !(weightSum > 0)) errors.add("traffic.bitRates: the sum of the weights must be > 0");
        }
    }

    private static void ignoredIfSet(List<String> warnings, String key, boolean set) {
        if (set) warnings.add(key + " is set but not used by the simulator (ignored)");
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
