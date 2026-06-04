# SNetS2: Spatial Network Simulator 2

SNetS2 is a high-performance, modular Discrete Event Simulator (DES) designed specifically for **Multi-core Elastic Optical Networks (MC-EON)**. With Spatial Division Multiplexing (SDM) treated as a first-class citizen, SNetS2 enables the evaluation of Routing, Modulation, Spectrum, and Core Assignment (RMSCA) algorithms under high-capacity and dynamic network traffic scenarios.

---

## 🚀 Key Features

* **Event-Driven Simulation (DES)**: Efficient Future Event List (FEL) priority-queue-based event loop processing events like connection arrival, setup, teardown, and blocking.
* **Spatial Division Multiplexing (SDM) & Multi-core Fiber (MCF)**: Built-in support for MCF links with configurable inter-core crosstalk and adjacent core topologies.
* **Spectrum Modeling**: High-performance spectrum slot availability representation leveraging Java's `BitSet`.
* **Flexible RMSCA Layer**: Pluggable strategies for routing (e.g., Dijkstra Shortest Path), core assignment (First-Fit, Random-Fit), and spectrum allocation.
* **Physical Layer Modeling**: Evaluates Quality of Transmission (QoT) including OSNR (ASE noise, non-linear impairments) and inter-core crosstalk (XT).
* **Comprehensive Metrics & Reporting**: Real-time evaluation of relative/external fragmentation, modulation format utilization, transmitter/receiver/regenerator usage, and blocking probability, exporting directly to structured multi-sheet Excel reports via Apache POI.
* **Experimental Planner**: Parameter sweep configuration allowing cartesian product generation of variables, parallel/sequential replication handling, and heterogeneous traffic setups.

---

## 🛠️ Technical Stack

* **Language**: Java 25 (utilizing modern language features).
* **High-Performance Collections**: [fastutil](https://fastutil.di.unimi.it/) for optimized memory usage and speed.
* **JSON Processing**: [Jackson](https://github.com/FasterXML/jackson) for Config-as-Code setups.
* **Exporting**: [Apache POI](https://poi.apache.org/) for generating rich `.xlsx` outputs.
* **Testing**: JUnit 5.

---

## 📂 Project Structure

```text
├── docs/                       # Technical documentation and formal mathematical descriptions
├── experiments/                # Configuration templates and experimental setups (JSON)
├── src/
│   ├── main/java/com/snets2/   # Source code
│   │   ├── engine/             # DES engine core & event loop lifecycle
│   │   ├── metrics/            # Metrics management & calculations
│   │   ├── model/              # Network topology models (Node, Link, Core, Spectrum, Transceiver)
│   │   ├── rmsca/              # Routing, Modulation, Spectrum, and Core Assignment logic
│   │   └── MainRunner.java     # Application entrypoint
│   └── test/java/com/snets2/   # Unit & integration tests
├── pom.xml                     # Maven configuration
└── GEMINI.md                   # Guidelines and standard requirements for development
```

---

## ⚙️ Getting Started

### Prerequisites

* **Java Development Kit (JDK) 25**
* **Apache Maven 3.9+**

### Installation & Build

Compile the project and run all unit tests:

```bash
mvn clean install
```

### Running Simulations

You can run experiments using the custom runner script or directly invoking Maven:

```bash
mvn exec:java -Dexec.mainClass="com.snets2.MainRunner" -Dexec.args="experiments/experiment01"
```

Alternatively, use the provided wrapper script:

```bash
chmod +x run_sim.sh
./run_sim.sh
```

---

## 📊 Documentation

Refer to the `docs/` directory for mathematical models, algorithms, and design systems:
* [Formal Description of Metrics](docs/formal_description/06_output_metrics.md)
* [Metrics System Implementation Plan](docs/implementation/05_metrics_system.md)
* [Project Development Roadmap & Status](docs/development_status.md)
