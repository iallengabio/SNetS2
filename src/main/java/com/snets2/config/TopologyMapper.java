package com.snets2.config;

import com.snets2.metrics.EnergyConsumptionModel;
import com.snets2.metrics.PhysicalLayerModel;
import com.snets2.model.*;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Responsible for converting Config POJOs into active Model entities.
 */
public class TopologyMapper {

    /**
     * Maps a NetworkTopologyConfig to a NetworkTopology model.
     *
     * @param config The topology configuration.
     * @param physConfig The physical layer configuration.
     * @param numSlots Standard number of slots per core.
     * @return A fully initialized NetworkTopology.
     */
    public static NetworkTopology map(NetworkTopologyConfig config, PhysicalLayerConfig physConfig, int numSlots) {
        // 1. Create Nodes
        List<Node> nodes = config.nodes().stream()
            .map(nc -> new Node(nc.id(), nc.tx(), nc.rx(), nc.regenerators(), nc.addDropDegree()))
            .collect(Collectors.toList());

        // 2. Map Modulations
        List<ModulationFormat> modulations = config.modulations().stream()
            .map(mc -> new ModulationFormat(mc.name(), mc.maxRange(), mc.M(), mc.SNR(), mc.XT(), 32.0, 0.1))
            .collect(Collectors.toList());

        // 3. Map Core Configurations for reuse across links
        List<CoreConfig> coreConfigs = config.cores();

        // 4. Create Links
        List<Link> links = new ArrayList<>();
        for (LinkConfig lc : config.links()) {
            List<Core> coresForLink = coreConfigs.stream()
                .map(cc -> new Core(cc.id(), cc.adjacentCores(), numSlots))
                .collect(Collectors.toList());

            links.add(new Link(lc.source(), lc.destination(), lc.length(), coresForLink,
                               buildAmplifiers(lc, physConfig)));
        }

        return new NetworkTopology(nodes, links, modulations);
    }

    /**
     * Builds the amplifier chain of a link with the same rule as the ASE model
     * ({@link PhysicalLayerModel#amplifierChainGainsDb}): a booster (3 x switchInsertionLoss dB),
     * N_l = ceil(L / L_span - 1) line amplifiers (alpha L_span dB) and a pre-amplifier (alpha (L - N_l L_span) dB).
     * Each one consumes {@link EnergyConsumptionModel#AMPLIFIER_POWER_W}.
     *
     * @return The N_l + 2 amplifiers of the link, in propagation order.
     */
    static List<Amplifier> buildAmplifiers(LinkConfig lc, PhysicalLayerConfig physConfig) {
        double[] gainsDb = PhysicalLayerModel.amplifierChainGainsDb(lc.length(), physConfig);
        List<Amplifier> amplifiers = new ArrayList<>(gainsDb.length);
        for (int i = 0; i < gainsDb.length; i++) {
            String role = (i == 0) ? "booster" : (i == gainsDb.length - 1) ? "pre" : "line" + i;
            amplifiers.add(new Amplifier("amp_" + lc.source() + "_" + lc.destination() + "_" + role,
                                         gainsDb[i],
                                         physConfig.noiseFigureOfOpticalAmplifier(),
                                         EnergyConsumptionModel.AMPLIFIER_POWER_W,
                                         physConfig.powerSaturationOfOpticalAmplifier()));
        }
        return amplifiers;
    }
}
