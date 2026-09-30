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

## 🧬 Lineage: SNetS v1

SNetS2 is a rewrite of **SNetS v1**, whose latest version is the SBRC 2026 artifact
[alexandrefontinele/SNetS-SDM-SBRC26](https://github.com/alexandrefontinele/SNetS-SDM-SBRC26).
SNetS2 inherits from it the `physicalLayer` configuration keys and the physical layer models (amplifier chain, gain
saturation and noise factor model, ROADM insertion loss, fixed/variable power spectral density, inter-core crosstalk),
adapted to an incremental noise cache. Deviations from v1 and the v1 keys that were dropped are documented in
[docs/formal_description/07_physical_layer_models.md](docs/formal_description/07_physical_layer_models.md).
RMSCA algorithms ported from v1 (with the deviations documented in
[docs/formal_description/04_rmsca_algorithms.md](docs/formal_description/04_rmsca_algorithms.md)):
`qot-margin` (`ModulationSelectionByQoTAndSigma`, SNR margin σ), `abne`/`abne2` (`CSBASDM`/`CSBASDM2`, core and spectrum
balancing), `cpcas`/`rccas` (core prioritization with spectrum zones), `icxtaa` (crosstalk-aware interval search), `xtawaregreedy` (largest crosstalk margin), `kspxt` (integrated, lowest XT + utilization cost).

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

Build the project and produce the standalone executable Fat JAR (`target/SNetS2-1.0-SNAPSHOT.jar`):

```bash
./build.sh
```

Or using Maven directly:

```bash
mvn clean package
```

*(To run tests during installation, you can also run `mvn clean install`)*.

### Running Simulations & GUI

#### 1. Running the Executable JAR
- **Graphical User Interface (GUI):**
  ```bash
  java -jar target/SNetS2-1.0-SNAPSHOT.jar
  ```
- **CLI Simulation:**
  ```bash
  java -jar target/SNetS2-1.0-SNAPSHOT.jar experiments/experiment01 [threads]
  ```

#### 2. Running via Script or Maven Exec
```bash
./run_sim.sh
```
or:
```bash
mvn exec:java -Dexec.mainClass="com.snets2.MainRunner" -Dexec.args="experiments/experiment01"
```

---

## 📊 Documentation

Refer to the `docs/` directory for mathematical models, algorithms, and design systems:
* [Formal Description of Metrics](docs/formal_description/06_output_metrics.md)
* [Metrics System Implementation Plan](docs/implementation/05_metrics_system.md)
* [Project Development Roadmap & Status](docs/development_status.md)
* [Technical Review: documentation, code review and verification plan](docs/review/README.md)
* [Verification & Validation report (Erlang B, Markov chain, RMSA algorithms, energy and physical layer)](docs/review/04_relatorio_verificacao_validacao.md)
