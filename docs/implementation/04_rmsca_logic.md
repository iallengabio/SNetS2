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
- **Contrato:** `candidateFormats(cp, path, bitRate)` devolve a lista ordenada de formatos que o `StandardIntegratedRMSCA` pode tentar. O RMSCA continua aplicando a restrição de alcance (`comprimento ≤ maxReach`), exceto quando há atribuição de regeneradores. `selectModulation` devolve só o primeiro formato viável.
- **DistanceAdaptiveModulationSelection** (ID: `distance-adaptive`, padrão quando `modulationSelection` é omitido): candidatos em ordem decrescente de eficiência espectral ($M$). Com o filtro de alcance, a primeira tentativa é o formato mais eficiente que alcança o destino; os menos eficientes servem de alternativa quando o espectro ou o QoT falham.
- **FixedModulationSelection** (ID: `fixed`): um único candidato, BPSK se disponível e, caso contrário, a primeira modulação listada na topologia, independentemente do comprimento do caminho.
- **SlotCalculator:** fonte única do número de slots, $n = \lceil R / (\log_2 M \cdot f_{slot}) \rceil + G$. É usado pelo RMSCA, pelas políticas de modulação e pela métrica de fragmentação relativa.
- **ModulationResult:** Encapsula o formato escolhido e a contagem de slots necessária.

### 1.3. Alocação de Core (`com.snets2.rmsca.core`)
- **ICoreAssignment:** Interface para seleção de núcleos espaciais.
- **FirstFitCoreAssignment:** Percorre os núcleos disponíveis e seleciona o primeiro índice que existe em todos os links do trajeto.
- **MinCrosstalkCoreAssignment:** Seleciona e ordena os núcleos com base no nível mínimo de interferência crosstalk inter-núcleo (calculado como o total de slots ocupados nos núcleos espacialmente adjacentes ao longo do caminho) (ID: `mincrosstalkcore` / `mincrosstalk`).

### 1.4. Atribuição de Espectro (`com.snets2.rmsca.spectrum`)
- **ISpectrumAssignment:** Interface para busca de slots contíguos.
- **FirstFitSpectrumAssignment:** Busca o primeiro bloco contíguo de slots que esteja livre em todos os enlaces do caminho simultaneamente, respeitando as restrições de rede elástica (EON).
- **DummyFitSpectrumAssignment:** Verifica apenas se o bloco de slots começando no índice 0 está disponível em todos os enlaces. Caso contrário, a requisição é bloqueada.
- **RandomFitSpectrumAssignment:** Sorteia uniformemente uma posição inicial entre todas as posições viáveis no caminho.
- **Reprodutibilidade (`RandomizedAlgorithm`):** `RandomFitSpectrumAssignment` e `RandomFitCoreAssignment` recebem, via `AlgorithmFactory.seedRandomizedAlgorithms`, geradores derivados da semente da replicação. Esses geradores são independentes do gerador de tráfego, então o fluxo de requisições é o mesmo entre algoritmos (números aleatórios comuns).
- **LastFitSpectrumAssignment:** Similar ao First Fit, mas inicia a busca a partir do fim do espectro (maior índice de slot) e retrocede, reduzindo colisões de alocação (ID: `lastfit` / `lf`).
- **ExactFitSpectrumAssignment:** Busca o bloco contíguo de slots que melhor se ajusta ao tamanho da requisição, priorizando blocos livres cujo tamanho é o mais próximo possível de `numSlots` para minimizar a fragmentação do espectro (ID: `exactfit` / `ef`).
- **SpectrumInterval:** Representa o intervalo `[start, end]` dos slots alocados.

---

## 2. Orquestração

### 2.1. `StandardIntegratedRMSCA`
Esta classe implementa a interface `IRMSCA` e atua como um coordenador sequencial:
1.  **Check:** Verifica disponibilidade de Tx/Rx nos nós.
2.  **Routing:** Invoca `IRouting`.
3.  **Passadas:** a passada 1 é transparente, só com formatos que alcançam o destino. A passada 2 só roda se houver `IRegeneratorAssignment` e a passada 1 falhar; ela tenta todos os formatos com regeneradores. Candidatos da passada 2 que não precisam de regenerador são pulados, porque já foram avaliados na passada 1.
4.  **Modulation:** obtém de `IModulationSelection.candidateFormats` a lista ordenada de formatos a tentar (padrão: `distance-adaptive`); o nº de slots vem do `SlotCalculator`.
5.  **Core:** Invoca `ICoreAssignment`.
6.  **Spectrum:** Invoca `ISpectrumAssignment`.
7.  **Validação (`evaluate`):** um único método para todos os candidatos. Verifica o SNR e o XT do novo circuito contra os limiares da sua modulação. Depois aplica temporariamente o ruído do candidato e verifica o SNR e o XT de cada circuito ativo, iterando a visão somente-leitura `ControlPlane.getActiveCircuitsView()`. O ruído temporário é removido num bloco `finally`.
8.  **Result:** Retorna um objeto `AllocationResult` contendo todos os detalhes técnicos da proposta de alocação ou da causa do bloqueio (nunca retorna `null`).

---

## 3. Resultado de Alocação (`AllocationResult`)

O objeto `AllocationResult` encapsula o resultado de uma tentativa de alocação efetuada pelo algoritmo RMSCA. Diferente da abordagem anterior (onde a falha retornava `null` e a causa era armazenada de forma transiente no Plano de Controle), o RMSCA agora retorna sempre uma instância de `AllocationResult` que descreve deterministicamente o sucesso ou o bloqueio.

### 3.1. Campos e Estrutura
- **`source` / `destination` / `bitRate`:** Informações originais da requisição de conexão.
- **`isBlocked`:** Flag indicando se a requisição foi bloqueada (`true`) ou estabelecida com sucesso (`false`).
- **`blockingCause`:** Instância de `BlockingCause` contendo a causa raiz do bloqueio (ex: `LACK_OF_TRANSMITTERS`, `NO_PATH`, `FRAGMENTATION`, `CROSSTALK`, etc.).
- **`blockingCoreId`:** Índice do núcleo onde ocorreu a falha espectral ou física (se aplicável, caso contrário `null`).
- **`path` / `coreIndices` / `startSlot` / `endSlot` / `modulation` / `regeneratorNodes`:** Parâmetros técnicos da alocação (válidos apenas se `isBlocked` for `false`).

### 3.2. Interações e Ciclo de Vida
1. **Geração:** O algoritmo RMSCA (ex: `StandardIntegratedRMSCA`) decide os parâmetros de alocação ou identifica o gargalo físico, instanciando `AllocationResult` através de seus construtores dedicados.
2. **Processamento no Evento de Chegada (`ArrivalEvent`):** O evento analisa `result.isBlocked()`. Se bem-sucedido, agenda um `SetupEvent` passando o resultado. Se bloqueado, extrai a causa e o núcleo de falha diretamente do `AllocationResult` e agenda um `BlockEvent`.
3. **Conversão para Circuito (`Circuit`):** O `SetupEvent` invoca `result.toCircuit(id)`, que valida o estado de sucesso e retorna um novo `Circuit` pronto para ser estabelecido no Plano de Controle.
