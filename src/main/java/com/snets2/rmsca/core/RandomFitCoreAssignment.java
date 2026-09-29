package com.snets2.rmsca.core;

import com.snets2.model.ControlPlane;
import com.snets2.model.Link;
import com.snets2.rmsca.routing.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Core assignment using the Random Fit policy.
 * It returns all valid core IDs in random order.
 */
public class RandomFitCoreAssignment implements ICoreAssignment, com.snets2.rmsca.RandomizedAlgorithm {
    // Fixed default seed keeps runs reproducible even when no seed is injected.
    private Random random = new Random(0);

    @Override
    public void setRandom(Random random) {
        this.random = random;
    }

    @Override
    public List<Integer> selectCores(ControlPlane cp, Path path) {
        if (path.links().isEmpty()) return Collections.emptyList();
        
        Link firstLink = path.links().get(0);
        List<Integer> candidates = new ArrayList<>();
        
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
        
        Collections.shuffle(candidates, random);
        return candidates;
    }
}
