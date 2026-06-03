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
     * @param numSlots Standard number of slots per core (could be added to physical layer config).
     * @return A fully initialized NetworkTopology.
     */
    public static NetworkTopology map(NetworkTopologyConfig config, int numSlots) {
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

            links.add(new Link(lc.source(), lc.destination(), lc.length(), coresForLink, new ArrayList<>()));
        }

        return new NetworkTopology(nodes, links, modulations);
    }
}
