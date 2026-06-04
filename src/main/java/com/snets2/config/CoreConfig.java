package com.snets2.config;

import java.util.List;

public record CoreConfig(
    int id,
    List<Integer> adjacentCores
) {}
