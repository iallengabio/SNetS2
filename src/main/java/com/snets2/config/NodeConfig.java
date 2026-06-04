package com.snets2.config;

public record NodeConfig(
    String id,
    int tx,
    int rx,
    int regenerators
) {}
