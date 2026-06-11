package com.snets2.rmsca.regenerator;

import com.snets2.model.*;
import com.snets2.rmsca.routing.Path;
import com.snets2.metrics.PhysicalLayerModel;
import com.snets2.config.PhysicalLayerConfig;
import java.util.ArrayList;
import java.util.List;

/**
 * As-Soon-As-Required (AAR) regenerator assignment.
 * Places a regenerator at the last possible node before reach or SNR constraints are violated.
 */
public class AsSoonAsRequiredRegeneratorAssignment implements IRegeneratorAssignment {

    @Override
    public List<Node> assignRegenerators(ControlPlane cp, Path path, int coreId, ModulationFormat mod, int startSlot, int endSlot, double bitRate) {
        List<Node> regeneratorNodes = new ArrayList<>();
        List<Link> currentSegmentLinks = new ArrayList<>();
        double currentDistance = 0.0;
        
        PhysicalLayerConfig physConfig = cp.getPhysicalLayerConfig();
        boolean checkQoT = physConfig != null && physConfig.activeQoT();

        for (int i = 0; i < path.links().size(); i++) {
            Link link = path.links().get(i);
            Node sourceNode = cp.getNode(link.getSourceId());

            // Check if adding this link violates maxReach or SNR
            currentSegmentLinks.add(link);
            currentDistance += link.getLength();
            
            boolean reachViolated = currentDistance > mod.maxReach();
            boolean qotViolated = false;

            if (!reachViolated && checkQoT) {
                Path segmentPath = new Path(currentSegmentLinks);
                double snr = PhysicalLayerModel.predictSNR(cp, segmentPath, coreId, startSlot, endSlot, mod, bitRate);
                if (snr < mod.getSnrThresholdLinear()) {
                    qotViolated = true;
                }
            }

            if (reachViolated || qotViolated) {
                // We must place a regenerator at the source of the current link (sourceNode)
                // If the current segment contains only this link, it means even a single link violates reach/QoT.
                if (currentSegmentLinks.size() == 1) {
                    return null; // Blocked
                }

                // Verify if sourceNode has available regenerators
                if (sourceNode.getAvailableRegenerators() <= 0) {
                    return null; // Blocked (no regenerators at this node)
                }

                // Add to selected regenerators
                regeneratorNodes.add(sourceNode);

                // Reset segment starting from sourceNode (which means current link is the first link of the new segment)
                currentSegmentLinks.clear();
                currentSegmentLinks.add(link);
                currentDistance = link.getLength();

                // Re-evaluate current link under the new segment to ensure it is valid on its own
                if (currentDistance > mod.maxReach()) {
                    return null; // Blocked
                }
                if (checkQoT) {
                    Path segmentPath = new Path(currentSegmentLinks);
                    double snr = PhysicalLayerModel.predictSNR(cp, segmentPath, coreId, startSlot, endSlot, mod, bitRate);
                    if (snr < mod.getSnrThresholdLinear()) {
                        return null; // Blocked
                    }
                }
            }
        }

        return regeneratorNodes;
    }
}
