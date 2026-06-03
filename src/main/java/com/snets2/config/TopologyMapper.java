package com.snets2.config;

import com.snets2.model.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
            .map(nc -> new Node(nc.id(), nc.tx(), nc.rx(), nc.regenerators()))
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

            // Calculate number of amplifiers based on spanLength
            List<Amplifier> amplifiers = new ArrayList<>();
            double spanLength = physConfig.spanLength();
            int numAmplifiers = (int) Math.floor(lc.length() / spanLength);
            for (int i = 0; i < numAmplifiers; i++) {
                // Default gains and NF from config could be added later
                amplifiers.add(new Amplifier("amp_" + lc.source() + "_" + lc.destination() + "_" + i, 
                                             16.0, 5.0, 100.0, 16.0));
            }

            links.add(new Link(lc.source(), lc.destination(), lc.length(), coresForLink, amplifiers));
        }

        return new NetworkTopology(nodes, links, modulations);
    }
}
