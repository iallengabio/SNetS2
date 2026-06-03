# Detalhamento Técnico: Lógica de Alocação (RMSCA)

Este documento descreve a implementação modular dos algoritmos de Roteamento, Modulação, Core e Espectro.

## 1. Arquitetura Modular

O SNetS2 utiliza interfaces granulares para permitir a troca de partes específicas do algoritmo de alocação.

### 1.1. `IRouting`
- **DijkstraRouting:** Calcula o caminho mais curto utilizando o algoritmo de Dijkstra, onde o peso das arestas é o comprimento físico do link (km).

### 1.2. `IModulationSelection`
- **DistanceAdaptiveModulationSelection:** Analisa o comprimento total do caminho e escolhe a modulação com maior bit rate cujo `maxReach` seja superior à distância. Calcula o número de slots necessários com base no `slotBandwidth`.

### 1.3. `ICoreAssignment`
- **FirstFitCoreAssignment:** Percorre os núcleos disponíveis e seleciona o primeiro índice que existe em todos os links do trajeto.

### 1.4. `ISpectrumAssignment`
- **FirstFitSpectrumAssignment:** Busca o primeiro bloco contíguo de slots que esteja livre em todos os enlaces do caminho simultaneamente, respeitando as restrições de rede elástica (EON).
- **DummyFitSpectrumAssignment:** Verifica apenas se o bloco de slots começando no índice 0 está disponível em todos os enlaces. Caso contrário, a requisição é bloqueada.
- **RandomFitSpectrumAssignment:** Seleciona aleatoriamente um dos blocos contíguos de slots disponíveis no caminho.

---

## 2. Orquestração

### 2.1. `StandardIntegratedRMSCA`
Esta classe implementa a interface `IRMSCA` e atua como um coordenador sequencial:
1.  **Check:** Verifica disponibilidade de Tx/Rx nos nós.
2.  **Routing:** Invoca `IRouting`.
3.  **Modulation:** Invoca `IModulationSelection`.
4.  **Core:** Invoca `ICoreAssignment`.
5.  **Spectrum:** Invoca `ISpectrumAssignment`.
6.  **Solution:** Retorna um objeto `AllocationSolution` contendo todos os detalhes técnicos da proposta de alocação.
