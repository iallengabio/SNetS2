# SNetS2 Project Guidelines

## Core Principles
- **Performance First:** Since it's a Discrete Event Simulator (DES) for large-scale networks, memory management and event processing speed are critical.
- **Modularity:** Separate the Simulation Engine, the Network Topology, and the RCSA (Routing, Core, and Spectrum Assignment) logic.
- **Scientific Rigor:** Every metric must be documented with its mathematical derivation.

## Technical Standards
- **Naming Conventions:** 
  - Classes: `PascalCase`
  - Methods/Variables: `camelCase`
  - Files: `snake_case`
- **Documentation:** All algorithms must be described in Markdown before implementation.
- **Language Decision:** TBD (Java vs C++). 
  - If Java: Use high-performance collections (e.g., fastutil).
  - If C++: Use Modern C++ (C++17/20) and smart pointers.

## Simulation Specifics
- Focus on MC-EON (Multi-core Elastic Optical Networks).
- Spatial Division Multiplexing (SDM) is a first-class citizen.

## Mandatory Project Rule: Documentation Sync
- **Strict Requirement:** Every functional implementation or architectural change **MUST** be reflected in the detailed documentation within the `docs/implementation/` directory.
- **Rationale:** This ensures a perfect mapping between the conceptual model and the code, facilitating long-term maintenance and allowing AI agents to understand and extend the system accurately.
- **Format:** Documentation must describe the class purpose, its mathematical/physical significance, and how it interacts with other components.
