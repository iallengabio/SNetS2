package com.snets2.verification;

import com.snets2.ExperimentalPlanner;
import com.snets2.config.ScenarioSetup;
import com.snets2.engine.SimulationEngine;
import com.snets2.output.SimulationResult;

import java.util.Map;

/**
 * Shared scaffolding for the verification tests (docs/review/03_plano_de_verificacao.md).
 */
final class VerificationSupport {

    private VerificationSupport() {}

    static final String MOD_4QAM = """
        {"name": "4QAM", "maxRange": 5000.0, "M": 4.0, "SNR": 8.95, "XT": -19.03}""";

    /**
     * Parameters of a two-node scenario (two opposite directed links, one core, QoT disabled).
     * Each direction receives {@code load / 2} Erlangs.
     */
    record TwoNode(int requests, int warmUp, int slots, double load, int guardBand, double bitRate,
                   String modulationsJson, String modulationSelection, String spectrumAssignment,
                   double linkLength) {

        /** 1 slot per request (25 Gbps, 4QAM, 12.5 GHz slots, no guard band). */
        static TwoNode oneSlotPerRequest(int requests, int warmUp, int slots, double load) {
            return new TwoNode(requests, warmUp, slots, load, 0, 25.0, MOD_4QAM, "fixed", "firstfit", 100.0);
        }

        TwoNode withModulations(String modulationsJson, String modulationSelection) {
            return new TwoNode(requests, warmUp, slots, load, guardBand, bitRate, modulationsJson,
                    modulationSelection, spectrumAssignment, linkLength);
        }

        TwoNode withSpectrumAssignment(String spectrumAssignment) {
            return new TwoNode(requests, warmUp, slots, load, guardBand, bitRate, modulationsJson,
                    modulationSelection, spectrumAssignment, linkLength);
        }

        TwoNode withBitRate(double bitRate, int guardBand) {
            return new TwoNode(requests, warmUp, slots, load, guardBand, bitRate, modulationsJson,
                    modulationSelection, spectrumAssignment, linkLength);
        }

        String json() {
            return """
            {
              "networkTopology": {
                "nodes": [
                  {"id": "0", "tx": 1000000, "rx": 1000000, "regenerators": 0},
                  {"id": "1", "tx": 1000000, "rx": 1000000, "regenerators": 0}
                ],
                "links": [
                  {"source": "0", "destination": "1", "length": %s},
                  {"source": "1", "destination": "0", "length": %s}
                ],
                "cores": [ {"id": 0, "adjacentCores": []} ],
                "modulations": [ %s ]
              },
              "physicalLayer": {
                "activeQoT": false, "activeQoTForOther": false,
                "guardBand": %d, "bvtSpectralWidth": 12.5E9, "spanLength": 80.0,
                "fiberLoss": 0.2, "constantOfPlanck": 6.626E-34, "amplificationFrequency": 1.9385E14,
                "noiseFigureOfOpticalAmplifier": 5.0
              },
              "simulation": {
                "requests": %d, "warmUpRequests": %d, "totalSlots": %d,
                "routing": "djk", "spectrumAssignment": "%s", "coreAndSpectrumAssignment": "firstfitcore",
                "integratedRMSCA": "standard", "modulationSelection": "%s",
                "activeMetrics": {
                  "BlockingProbability": true, "SpectrumUtilization": true,
                  "ExternalFragmentation": false, "RelativeFragmentation": false,
                  "TransmittersReceiversRegeneratorsUtilization": false, "ModulationUtilization": true,
                  "SpectrumSizeStatistics": false, "ConsumedEnergy": false,
                  "SimulationMetadata": true, "CrosstalkStatistics": false
                }
              },
              "traffic": { "loadDistributionPerPair": "uniform", "load": %s,
                           "bitRates": [ {"value": %s, "weight": 1.0} ] },
              "experimentalPlanning": { "replications": 1 }
            }
            """.formatted(Double.toString(linkLength), Double.toString(linkLength), modulationsJson,
                    guardBand, requests, warmUp, slots, spectrumAssignment, modulationSelection,
                    Double.toString(load), Double.toString(bitRate));
        }
    }

    /** Builds and runs one replication through {@link ExperimentalPlanner#runReplication}. */
    static SimulationEngine runReplication(ScenarioSetup setup, long seed) {
        return ExperimentalPlanner.runReplication(setup, (int) seed, new SimulationResult(1), Map.of());
    }
}
