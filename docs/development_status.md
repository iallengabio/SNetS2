# SNetS2: Status do Desenvolvimento e Roadmap

Este documento descreve o estado atual da implementação do simulador e define os próximos passos para alcançar a versão funcional completa (MVP).

## 1. Estado Atual (Concluído)

### 1.1. Network Model (Módulo de Sistema)
- [x] **Modelagem de Espectro:** Implementado via `BitSet` para alta performance.
- [x] **SDM Support:** Suporte nativo a fibras multicore com adjacências configuráveis.
- [x] **Hardware Management:** Controle de Transmissores, Receivers e Regeneradores nos nós (ROADMs).
- [x] **Physical Entities:** Implementação de classes para `Link`, `Amplifier` e `ModulationFormat`.
- [x] **Control Plane:** Centralização do estado da rede e ciclo de vida de circuitos (`Circuit`).

### 1.2. Simulation Engine (DES)
- [x] **Motor de Eventos:** Implementação da Future Event List (FEL) baseada em Fila de Prioridade.
- [x] **Tipologia de Eventos:** Implementados `Arrival`, `Setup`, `Departure`, `Teardown` e `Block`.
- [x] **Processos Estocásticos:** Utilidade `RandomGenerator` para processos de Poisson (chegadas) e tempos de retenção exponenciais.
- [x] **Arquitetura Hierárquica:** `SimulationEngine` centraliza acesso à topologia, plano de controle e algoritmos.

### 1.3. Infraestrutura e Qualidade
- [x] **Documentação:** Javadoc completo em todas as classes principais e [Documentação Detalhada de Implementação](../docs/implementation/README.md).
- [x] **Validação:** Testes de integração (`SimulationLifecycleTest`) comprovando a execução correta da cadeia de eventos.

---

## 2. Próximos Passos (Roadmap)

### Fase 2: Configuração e RMSCA (Concluída)
- [x] **Config-as-Code (JSON Parser):**
  - Implementado o leitor do objeto `experimentSetup` usando Jackson.
  - Implementada a lógica de mapeamento para instâncias reais de Topologia.
  - Implementada a `AlgorithmFactory` para instanciação dinâmica via Reflection.
- [x] **Algoritmos Base (RMSCA):**
  - Implementado Roteamento Dijkstra (Shortest Path por distância).
  - Implementada Atribuição de Espectro First Fit, Random Fit e Dummy Fit.
  - Implementada Atribuição de Core First Fit.
  - Implementada Seleção de Modulação Adaptativa por Distância.
  - Implementada a orquestração `StandardIntegratedRMSCA`.

### Fase 3: Camada Física e Consumo Energético (Concluída)
- [x] **Physical Layer Model:**
  - Implementar cálculo de OSNR (ASE Noise e NLI).
  - Implementar modelo matemático de Crosstalk (XT) inter-core.
  - Integração de validação de QoT durante o `ArrivalEvent`.
- [x] **Energy Consumption Model:**
  - Implementar o modelo de consumo de potência por componente (Transceptores, Amplificadores, Nós e Regeneradores).
  - Lógica para computar o consumo acumulado e instantâneo da rede.

### Fase 4: Métricas e Saída (Concluída)
- [x] **Metrics Manager:**
  - Coleta de estatísticas de bloqueio (Bit Rate), utilização de espectro.
  - Implementação de `ObserverEvents` para métricas temporais.
- [x] **Excel/CSV Export:**
  - Integração com Apache POI para geração de planilhas multi-abas (`results.xlsx`).

### Fase 5: Planejamento Experimental (Concluída)
- [x] **Experimental Planning:**
  - Lógica de Parameter Sweep (Produto Cartesiano de parâmetros).
  - Gerenciamento de múltiplas replicações e paralelismo (via loops sequenciais nesta v1).
  - Suporte a tráfego heterogêneo (múltiplas larguras de banda com pesos).
  - Sistema de Checkpointing (preparado estruturalmente).

---

## 3. Notas Técnicas
- **Linguagem:** Java 25.
- **Paradigma:** Discrete Event Simulation (DES).
- **Foco:** Performance extrema e modularidade para MC-EON.
