package com.snets2.metrics;

/**
 * Enumerates the possible root causes for a request blocking.
 */
public enum BlockingCause {
    LACK_OF_TRANSMITTERS,
    LACK_OF_RECEIVERS,
    FRAGMENTATION,
    QOT_NEW,      // Signal quality of the new connection is insufficient
    QOT_OTHERS,   // New connection would degrade existing ones below threshold
    CROSSTALK,    // Specific XT failure for the new connection
    XT_OTHERS,    // New connection causes excessive XT on others
    NO_PATH,
    OTHER
}
