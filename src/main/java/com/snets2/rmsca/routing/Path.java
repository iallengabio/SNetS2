package com.snets2.rmsca.routing;

import com.snets2.model.Link;
import java.util.List;

/**
 * Represents a path in the network, consisting of an ordered list of links.
 */
public record Path(List<Link> links) {
    /**
     * Calculates the total physical length of the path in km.
     * @return Total length.
     */
    public double getLength() {
        return links.stream().mapToDouble(Link::getLength).sum();
    }

    /** @return Number of links (hops) in the path. */
    public int getHops() {
        return links.size();
    }
}
