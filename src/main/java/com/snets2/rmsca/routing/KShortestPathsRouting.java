package com.snets2.rmsca.routing;

import com.snets2.model.ControlPlane;
import com.snets2.model.Link;
import com.snets2.model.Node;
import java.util.*;

/**
 * k-Shortest Paths (KSP) routing algorithm using Yen's algorithm.
 */
public class KShortestPathsRouting implements IRouting {
    private final int k;

    public KShortestPathsRouting() {
        this(3); // Default to 3 paths
    }

    public KShortestPathsRouting(int k) {
        this.k = k;
    }

    @Override
    public List<Path> findPaths(ControlPlane cp, Node source, Node destination) {
        List<Path> A = new ArrayList<>();
        PriorityQueue<Path> B = new PriorityQueue<>(Comparator.comparingDouble(Path::getLength));

        // Find the first shortest path
        Path firstPath = findShortestPath(cp, source.getId(), destination.getId(), Collections.emptySet(), Collections.emptySet());
        if (firstPath == null) {
            return A;
        }
        A.add(firstPath);

        for (int kIdx = 1; kIdx < k; kIdx++) {
            Path prevPath = A.get(kIdx - 1);
            for (int i = 0; i < prevPath.links().size(); i++) {
                String spurNodeId = i == 0 ? source.getId() : prevPath.links().get(i - 1).getDestinationId();

                List<Link> rootPathLinks = new ArrayList<>();
                for (int j = 0; j < i; j++) {
                    rootPathLinks.add(prevPath.links().get(j));
                }

                Set<String> blockedNodeIds = new HashSet<>();
                Set<String> blockedLinkKeys = new HashSet<>();

                for (Path p : A) {
                    if (p.links().size() > i) {
                        boolean match = true;
                        for (int j = 0; j < i; j++) {
                            if (!p.links().get(j).getSourceId().equals(rootPathLinks.get(j).getSourceId()) ||
                                !p.links().get(j).getDestinationId().equals(rootPathLinks.get(j).getDestinationId())) {
                                match = false;
                                break;
                            }
                        }
                        if (match) {
                            Link linkToRemove = p.links().get(i);
                            blockedLinkKeys.add(linkToRemove.getSourceId() + "->" + linkToRemove.getDestinationId());
                        }
                    }
                }

                for (int j = 0; j < i; j++) {
                    String nodeId = j == 0 ? source.getId() : prevPath.links().get(j - 1).getDestinationId();
                    if (!nodeId.equals(spurNodeId)) {
                        blockedNodeIds.add(nodeId);
                    }
                }

                Path spurPath = findShortestPath(cp, spurNodeId, destination.getId(), blockedNodeIds, blockedLinkKeys);
                if (spurPath != null) {
                    List<Link> totalPathLinks = new ArrayList<>(rootPathLinks);
                    totalPathLinks.addAll(spurPath.links());
                    Path totalPath = new Path(totalPathLinks);
                    if (!A.contains(totalPath) && B.stream().noneMatch(p -> p.equals(totalPath))) {
                        B.add(totalPath);
                    }
                }
            }

            if (B.isEmpty()) {
                break;
            }
            A.add(B.poll());
        }

        return A;
    }

    private Path findShortestPath(ControlPlane cp, String sourceId, String destinationId, Set<String> blockedNodeIds, Set<String> blockedLinkKeys) {
        if (blockedNodeIds.contains(sourceId) || blockedNodeIds.contains(destinationId)) {
            return null;
        }

        Map<String, Double> distances = new HashMap<>();
        Map<String, Link> previousLink = new HashMap<>();
        PriorityQueue<NodeDistance> queue = new PriorityQueue<>(Comparator.comparingDouble(nd -> nd.distance));

        for (Node node : cp.getNodes()) {
            distances.put(node.getId(), Double.MAX_VALUE);
        }

        distances.put(sourceId, 0.0);
        queue.add(new NodeDistance(sourceId, 0.0));

        while (!queue.isEmpty()) {
            NodeDistance current = queue.poll();

            if (current.distance > distances.get(current.id)) continue;
            if (current.id.equals(destinationId)) break;

            for (Link link : cp.getLinks()) {
                if (link.getSourceId().equals(current.id)) {
                    String nextNodeId = link.getDestinationId();
                    if (blockedNodeIds.contains(nextNodeId)) continue;
                    
                    String linkKey = link.getSourceId() + "->" + link.getDestinationId();
                    if (blockedLinkKeys.contains(linkKey)) continue;

                    double newDist = distances.get(current.id) + link.getLength();
                    if (newDist < distances.get(nextNodeId)) {
                        distances.put(nextNodeId, newDist);
                        previousLink.put(nextNodeId, link);
                        queue.add(new NodeDistance(nextNodeId, newDist));
                    }
                }
            }
        }

        List<Link> pathLinks = new ArrayList<>();
        String currentId = destinationId;
        while (previousLink.containsKey(currentId)) {
            Link link = previousLink.get(currentId);
            pathLinks.add(0, link);
            currentId = link.getSourceId();
        }

        if (pathLinks.isEmpty() || !currentId.equals(sourceId)) {
            return null;
        }

        return new Path(pathLinks);
    }

    private record NodeDistance(String id, double distance) {}
}
