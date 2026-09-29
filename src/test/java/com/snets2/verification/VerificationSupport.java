package com.snets2.verification;

import com.snets2.config.ScenarioSetup;
import com.snets2.config.TopologyMapper;
import com.snets2.engine.ArrivalEvent;
import com.snets2.engine.ResourceUtilizationObservationEvent;
import com.snets2.engine.SimulationEngine;
import com.snets2.model.ControlPlane;
import com.snets2.model.NetworkTopology;
import com.snets2.model.Node;
import com.snets2.rmsca.AlgorithmFactory;
import com.snets2.rmsca.IRMSCA;
import com.snets2.rmsca.StandardIntegratedRMSCA;

import java.util.List;

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

    /** Builds and runs one replication exactly as {@code ExperimentalPlanner} does. */
    static SimulationEngine runReplication(ScenarioSetup setup, long seed) {
        NetworkTopology topology = TopologyMapper.map(setup.networkTopology(), setup.physicalLayer(),
                setup.simulation().totalSlots());
        IRMSCA rmsca = AlgorithmFactory.createIntegrated(setup.simulation().integratedRMSCA());
        StandardIntegratedRMSCA standard = (StandardIntegratedRMSCA) rmsca;
        standard.setRouting(AlgorithmFactory.createRouting(setup.simulation().routing()));
        standard.setModulationSelection(AlgorithmFactory.createModulation(setup.simulation().modulationSelection()));
        standard.setCoreAssignment(AlgorithmFactory.createCore(setup.simulation().coreAndSpectrumAssignment()));
        standard.setSpectrumAssignment(AlgorithmFactory.createSpectrum(setup.simulation().spectrumAssignment()));
        AlgorithmFactory.seedRandomizedAlgorithms(standard, seed);

        ControlPlane cp = new ControlPlane(topology, rmsca, setup.physicalLayer().bvtSpectralWidth(),
                setup.physicalLayer().guardBand(), setup.physicalLayer());
        SimulationEngine engine = new SimulationEngine(topology, cp, setup.simulation().requests(),
                setup.simulation().warmUpRequests(), setup.simulation().activeMetrics(),
                setup.traffic().load(), setup.traffic().bitRates(), seed);

        List<Node> nodes = topology.nodes();
        Node src = nodes.get(engine.getRandom().nextInt(nodes.size()));
        Node dst;
        do {
            dst = nodes.get(engine.getRandom().nextInt(nodes.size()));
        } while (src == dst);
        engine.schedule(new ArrivalEvent(0.0, src, dst, engine.nextBitRate()));
        engine.schedule(new ResourceUtilizationObservationEvent(0.0));
        engine.run();
        return engine;
    }
}
