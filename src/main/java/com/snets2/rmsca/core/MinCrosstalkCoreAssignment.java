package com.snets2.rmsca.core;

import com.snets2.model.ControlPlane;
import com.snets2.model.Core;
import com.snets2.model.Link;
import com.snets2.rmsca.routing.Path;
import java.util.*;

/**
 * Core assignment strategy that minimizes inter-core crosstalk.
 * It selects core IDs based on the minimum occupancy of adjacent cores along the path.
 */
public class MinCrosstalkCoreAssignment implements ICoreAssignment {

    @Override
    public List<Integer> selectCores(ControlPlane cp, Path path) {
        if (path.links().isEmpty()) return Collections.emptyList();

        Link firstLink = path.links().get(0);
        List<Integer> candidates = new ArrayList<>();

        // Find core IDs that exist in all links of the path
        for (Integer coreId : firstLink.getCores().keySet()) {
            boolean allHaveIt = true;
            for (Link link : path.links()) {
                if (!link.getCores().containsKey(coreId)) {
                    allHaveIt = false;
                    break;
                }
            }
            if (allHaveIt) candidates.add(coreId);
        }

        // Calculate adjacent occupancy sum for each candidate core ID
        Map<Integer, Integer> adjacentOccupancy = new HashMap<>();
        for (Integer coreId : candidates) {
            int totalAdjacentOccupied = 0;
            for (Link link : path.links()) {
                Core core = link.getCore(coreId);
                if (core != null) {
                    for (int adjId : core.getAdjacentCores()) {
                        Core adjCore = link.getCore(adjId);
                        if (adjCore != null) {
                            totalAdjacentOccupied += adjCore.getSpectrum().getSlots().cardinality();
                        }
                    }
                }
            }
            adjacentOccupancy.put(coreId, totalAdjacentOccupied);
        }

        // Sort candidates by adjacent occupancy (ascending), and sub-sort numerically by core ID
        candidates.sort((c1, c2) -> {
            int cmp = Integer.compare(adjacentOccupancy.get(c1), adjacentOccupancy.get(c2));
            if (cmp != 0) return cmp;
            return Integer.compare(c1, c2);
        });

        return candidates;
    }
}
