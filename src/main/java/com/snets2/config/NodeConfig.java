package com.snets2.config;

import com.snets2.model.Node;

/**
 * Configuration of a network node (ROADM), JSON key {@code networkTopology.nodes[]}.
 *
 * @param id            Unique node identifier.
 * @param tx            Number of installed transmitters (BVTs).
 * @param rx            Number of installed receivers (BVTs).
 * @param regenerators  Number of installed regenerators.
 * @param addDropDegree Add/drop degree of the ROADM (number of add/drop ports), the term {@code a} of the OXC
 *                      power 85 n + 100 a + 150 W (Vizcaino et al., Computer Networks 56, 2012). Optional: when
 *                      omitted it is {@link Node#DEFAULT_ADD_DROP_DEGREE}. Independent of {@code tx}/{@code rx}.
 */
public record NodeConfig(
    String id,
    int tx,
    int rx,
    int regenerators,
    Integer addDropDegree
) {
    public NodeConfig {
        if (addDropDegree == null) addDropDegree = Node.DEFAULT_ADD_DROP_DEGREE;
    }

    /** Node with the default add/drop degree. */
    public NodeConfig(String id, int tx, int rx, int regenerators) {
        this(id, tx, rx, regenerators, null);
    }
}
