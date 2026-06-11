# Detalhamento Técnico: Lógica de Alocação (RMSCA)

Este documento descreve a implementação modular dos algoritmos de Roteamento, Modulação, Core e Espectro.

## 1. Arquitetura Modular

O SNetS2 utiliza interfaces granulares organizadas em subpacotes dentro de `com.snets2.rmsca` para permitir a troca de partes específicas do algoritmo de alocação.

### 1.1. Roteamento (`com.snets2.rmsca.routing`)
- **IRouting:** Interface base para algoritmos de busca de caminho.
- **DijkstraRouting:** Calcula o caminho mais curto utilizando o algoritmo de Dijkstra, onde o peso das arestas é o comprimento físico do link (km).
- **KShortestPathsRouting:** Calcula os $k$ caminhos mais curtos utilizando o algoritmo de Yen. Retorna uma lista ordenada de caminhos candidatos para aumentar as chances de sucesso de alocação de recursos (ID: `newksp` / `ksp`).
- **Path:** Registro que encapsula uma lista de links e fornece métodos utilitários como `getLength()`.

### 1.2. Seleção de Modulação (`com.snets2.rmsca.modulation`)
- **IModulationSelection:** Interface para escolha do formato de modulação.
- **DistanceAdaptiveModulationSelection:** Analisa o comprimento total do caminho e escolhe a modulação com maior bit rate cujo `maxReach` seja superior à distância. Calcula o número de slots necessários com base no `slotBandwidth`.
- **FixedModulationSelection:** Sempre seleciona um formato de modulação fixo (BPSK se disponível, caso contrário a primeira modulação listada na topologia) independente do comprimento do caminho (ID: `fixed`).
- **ModulationResult:** Encapsula o formato escolhido e a contagem de slots necessária.

### 1.3. Alocação de Core (`com.snets2.rmsca.core`)
- **ICoreAssignment:** Interface para seleção de núcleos espaciais.
- **FirstFitCoreAssignment:** Percorre os núcleos disponíveis e seleciona o primeiro índice que existe em todos os links do trajeto.
- **MinCrosstalkCoreAssignment:** Seleciona e ordena os núcleos com base no nível mínimo de interferência crosstalk inter-núcleo (calculado como o total de slots ocupados nos núcleos espacialmente adjacentes ao longo do caminho) (ID: `mincrosstalkcore` / `mincrosstalk`).

### 1.4. Atribuição de Espectro (`com.snets2.rmsca.spectrum`)
- **ISpectrumAssignment:** Interface para busca de slots contíguos.
- **FirstFitSpectrumAssignment:** Busca o primeiro bloco contíguo de slots que esteja livre em todos os enlaces do caminho simultaneamente, respeitando as restrições de rede elástica (EON).
- **DummyFitSpectrumAssignment:** Verifica apenas se o bloco de slots começando no índice 0 está disponível em todos os enlaces. Caso contrário, a requisição é bloqueada.
- **RandomFitSpectrumAssignment:** Seleciona aleatoriamente um dos blocos contíguos de slots disponíveis no caminho.
- **LastFitSpectrumAssignment:** Similar ao First Fit, mas inicia a busca a partir do fim do espectro (maior índice de slot) e retrocede, reduzindo colisões de alocação (ID: `lastfit` / `lf`).
- **ExactFitSpectrumAssignment:** Busca o bloco contíguo de slots que melhor se ajusta ao tamanho da requisição, priorizando blocos livres cujo tamanho é o mais próximo possível de `numSlots` para minimizar a fragmentação do espectro (ID: `exactfit` / `ef`).
- **SpectrumInterval:** Representa o intervalo `[start, end]` dos slots alocados.

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
