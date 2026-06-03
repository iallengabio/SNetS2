package com.snets2.rmsca;

import com.snets2.model.ControlPlane;
import com.snets2.model.Node;
import java.util.List;

/** Interface for Routing algorithms. */
public interface IRouting {
    /**
     * Finds paths between source and destination.
     * @return List of candidate paths.
     */
    List<Path> findPaths(ControlPlane cp, Node source, Node destination);
}
