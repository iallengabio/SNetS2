package com.snets2.rmsca.routing;

import com.snets2.model.ControlPlane;
import com.snets2.model.Link;
import com.snets2.model.Node;
import java.util.*;

/**
 * Shortest path routing using Dijkstra's algorithm.
 * Weight is based on link length (km).
 */
public class DijkstraRouting implements IRouting {

    @Override
    public List<Path> findPaths(ControlPlane cp, Node source, Node destination) {
        Map<String, Double> distances = new HashMap<>();
        Map<String, Link> previousLink = new HashMap<>();
        PriorityQueue<NodeDistance> queue = new PriorityQueue<>(Comparator.comparingDouble(nd -> nd.distance));

        for (Node node : cp.getNodes()) {
            distances.put(node.getId(), Double.MAX_VALUE);
        }

        distances.put(source.getId(), 0.0);
        queue.add(new NodeDistance(source.getId(), 0.0));

        while (!queue.isEmpty()) {
            NodeDistance current = queue.poll();

            if (current.distance > distances.get(current.id)) continue;
            if (current.id.equals(destination.getId())) break;

            // Find outgoing links
            for (Link link : cp.getLinks()) {
                if (link.getSourceId().equals(current.id)) {
                    double newDist = distances.get(current.id) + link.getLength();
                    if (newDist < distances.get(link.getDestinationId())) {
                        distances.put(link.getDestinationId(), newDist);
                        previousLink.put(link.getDestinationId(), link);
                        queue.add(new NodeDistance(link.getDestinationId(), newDist));
                    }
                }
            }
        }

        // Reconstruct path
        List<Link> pathLinks = new ArrayList<>();
        String currentId = destination.getId();
        while (previousLink.containsKey(currentId)) {
            Link link = previousLink.get(currentId);
            pathLinks.add(0, link);
            currentId = link.getSourceId();
        }

        if (pathLinks.isEmpty() || !currentId.equals(source.getId())) {
            return Collections.emptyList();
        }

        return List.of(new Path(pathLinks));
    }

    private record NodeDistance(String id, double distance) {}
}
