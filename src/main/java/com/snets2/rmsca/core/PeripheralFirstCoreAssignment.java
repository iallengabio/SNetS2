package com.snets2.rmsca.core;

import com.snets2.model.ControlPlane;
import com.snets2.model.Core;
import com.snets2.model.Link;
import com.snets2.rmsca.routing.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Core assignment with a fixed, peripheral-first priority order.
 *
 * <p>The order is built greedily from the fibre layout (adjacency of the first link of the path):
 * at each step it picks the remaining core with the fewest neighbours among the cores already
 * ordered, then the fewest neighbours overall, then the lowest id. Cores that share no neighbour
 * relation are therefore filled first. For the 7-core hexagonal MCF (core 0 in the centre) the
 * order is {@code 1, 3, 5, 2, 4, 6, 0}: the three non-adjacent outer cores, the other three outer
 * cores, and the central core (6 neighbours) last.</p>
 */
public class PeripheralFirstCoreAssignment implements ICoreAssignment {

    @Override
    public List<Integer> selectCores(ControlPlane cp, Path path) {
        return peripheralOrder(path);
    }

    /** Cores present in every link of the path, in peripheral-first order (see class comment). */
    public static List<Integer> peripheralOrder(Path path) {
        List<Integer> remaining = commonCores(path);
        if (remaining.isEmpty()) return remaining;
        Link firstLink = path.links().get(0);

        List<Integer> ordered = new ArrayList<>(remaining.size());
        Set<Integer> chosen = new HashSet<>();
        while (!remaining.isEmpty()) {
            Integer best = null;
            int bestConflicts = Integer.MAX_VALUE;
            int bestDegree = Integer.MAX_VALUE;
            for (Integer coreId : remaining) { // remaining is sorted by id: ties keep the lowest id
                List<Integer> adjacent = firstLink.getCore(coreId).getAdjacentCores();
                int conflicts = 0;
                for (int adj : adjacent) if (chosen.contains(adj)) conflicts++;
                int degree = adjacent.size();
                if (conflicts < bestConflicts || (conflicts == bestConflicts && degree < bestDegree)) {
                    best = coreId;
                    bestConflicts = conflicts;
                    bestDegree = degree;
                }
            }
            ordered.add(best);
            chosen.add(best);
            remaining.remove(best);
        }
        return ordered;
    }

    /**
     * Greedy colouring of the cores of the path's fibre: following the peripheral-first order, each core
     * receives the smallest colour not used by an already coloured adjacent core. Cores with the same
     * colour are never adjacent. For the 7-core hexagonal MCF: cores 1, 3, 5 get colour 0, cores 2, 4, 6
     * colour 1 and the central core 0 colour 2.
     *
     * @return map core id -> colour (0-based)
     */
    public static Map<Integer, Integer> coreColours(Path path) {
        Map<Integer, Integer> colours = new HashMap<>();
        if (path.links().isEmpty()) return colours;
        Link firstLink = path.links().get(0);
        for (Integer coreId : peripheralOrder(path)) {
            Set<Integer> used = new HashSet<>();
            for (int adj : firstLink.getCore(coreId).getAdjacentCores()) {
                Integer c = colours.get(adj);
                if (c != null) used.add(c);
            }
            int colour = 0;
            while (used.contains(colour)) colour++;
            colours.put(coreId, colour);
        }
        return colours;
    }

    /** Core ids present in every link of the path, sorted numerically. */
    static List<Integer> commonCores(Path path) {
        if (path.links().isEmpty()) return new ArrayList<>();
        List<Integer> candidates = new ArrayList<>();
        for (Integer coreId : path.links().get(0).getCores().keySet()) {
            boolean allHaveIt = true;
            for (Link link : path.links()) {
                Core core = link.getCore(coreId);
                if (core == null) {
                    allHaveIt = false;
                    break;
                }
            }
            if (allHaveIt) candidates.add(coreId);
        }
        Collections.sort(candidates);
        return candidates;
    }
}
