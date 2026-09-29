# Fluxo do Algoritmo `StandardIntegratedRMSCA`

Este documento explica o fluxo detalhado de execução do algoritmo de RMSCA (Routing, Modulation, Spectrum, and Core Allocation) integrado em [StandardIntegratedRMSCA.java](file:///Users/iallen/Dev/java/SNetS2/src/main/java/com/snets2/rmsca/StandardIntegratedRMSCA.java), descrevendo a ordem de execução dos sub-algoritmos e os critérios de decisão para o sucesso ou bloqueio da requisição.

---

## 1. Ordem de Execução e Sub-Algoritmos

O algoritmo segue uma estratégia estruturada em etapas lógicas, executadas de forma sequencial e em laços aninhados:

1. **Checagem de Hardware de Nós**: Validação de transmissores (Tx) e receptores (Rx) disponíveis.
2. **Roteamento (IRouting)**: Busca de caminhos candidatos.
3. **Loop de Caminhos**: Avaliação individual de cada trajeto físico retornado pelo roteamento.
4. **Loop de Modulação (IModulationSelection)**: Varredura de formatos de modulação disponíveis, ordenados por eficiência espectral decrescente. Com `qot-adaptive`, o alcance (`maxRange`) não filtra os formatos: o primeiro formato que passa na validação de QoT é aceito.
5. **Cálculo de Demanda Espectral**: Determinação do número de slots de espectro requeridos.
6. **Loop de Núcleos (ICoreAssignment)**: Varredura de núcleos candidatos do cabo de fibra multicore, na ordem dada por `selectCores(cp, path, numSlots, spectrumAssignment)`. Estratégias sensíveis ao slot (`xtawarecore`) usam o número de slots e a política espectral para ordenar os núcleos pelo crosstalk no intervalo que cada um receberia.
7. **Atribuição Espectral (ISpectrumAssignment)**: Busca de slots contíguos e contínuos livres.
8. **Atribuição de Regeneradores (IRegeneratorAssignment)**: só na segunda passada, quando nenhuma solução transparente foi encontrada.
9. **Validação de QoT (Quality of Transmission)**:
   - **Canal Próprio**: Predição de SNR linear e crosstalk inter-núcleo (XT).
   - **Canais Vizinhos**: Predição do impacto de interferência nas conexões já ativas na rede.

---

## 2. Passo a Passo Detalhado do Fluxo

```
[Início]
   │
   ├──> 1. Validação de Transmissores no Nó de Origem (Tx)
   │       Se não houver Tx livre ──> [BLOQUEIO: LACK_OF_TRANSMITTERS]
   │
   ├──> 2. Validação de Receptores no Nó de Destino (Rx)
   │       Se não houver Rx livre ──> [BLOQUEIO: LACK_OF_RECEIVERS]
   │
   ├──> 3. Cálculo de Caminhos (IRouting)
   │       Se a lista de caminhos candidatos for vazia ──> [BLOQUEIO: NO_PATH]
   │
   └──> 4. Duas passadas: (P1) transparente; (P2) com regeneradores, só se P1 falhar
           │     e houver `regeneratorAssignment` configurado
           │
           └──> 5. Laço: Iterar sobre cada Caminho Candidato
                   │
                   └──> 6. Laço: Iterar sobre cada Modulação (ordem dada por IModulationSelection.candidateFormats)
                           │
                           ├──> a. Teste de Alcance Físico (Reach)
                           │       P1: se Distância > Alcance Máximo ──> Pular Modulação
                           │           (não se aplica a qot-adaptive com activeQoT: nenhuma é pulada)
                           │       P2: todas as modulações são tentadas (com regeneração)
                           │
                           ├──> b. Número de slots: ⌈R(1+FEC)/(N_pol·log2 M·f_slot)⌉ + guarda (SlotCalculator)
                           │
                           └──> c. Laço: Iterar sobre cada Núcleo (Core) Candidato (ordem de selectCores com o número de slots)
                                   │
                                   ├──> i. Atribuição Espectral (ISpectrumAssignment)
                                   │       Se não encontrar ──> Pular Core (causa provisória: FRAGMENTATION)
                                   │
                                   ├──> ii. (P2) Alocação de Regeneradores (AAR: por alcance e por SNR; só por SNR com qot-adaptive)
                                   │         Se falhar, ou se não precisar de regenerador (já avaliado em P1) ──> Pular Core
                                   │
                                   ├──> iii. evaluate(): validação física do candidato (se activeQoT)
                                   │         - SNR do novo canal < limiar ──> CROSSTALK (se passaria sem XT) ou QOT_NEW
                                   │         - XT do novo canal > limiar XT da modulação ──> CROSSTALK
                                   │         - Aplica o ruído temporário do candidato (NLI/XT) e, para cada circuito ativo
                                   │           que usa um enlace do candidato no mesmo núcleo ou num núcleo adjacente:
                                   │             SNR < limiar ──> XT_OTHERS (se passaria sem XT) ou QOT_OTHERS
                                   │             XT > limiar XT da sua modulação ──> XT_OTHERS
                                   │           (o ruído temporário é removido num bloco finally)
                                   │         Se falhar ──> Pular Core
                                   │
                                   └──> iv. SUCESSO DE ALOCAÇÃO
                                           Retorna imediatamente AllocationResult de sucesso.
```

Consequências do desenho em duas passadas:
* Uma modulação menos eficiente que alcança o destino **sem** regeneração é sempre preferida a uma solução regenerada.
* Todo candidato, inclusive os regenerados, passa pela mesma validação da QoT dos circuitos ativos.

Precedência: caminho → formato → núcleo. Um formato só é rebaixado depois de tentado em todos os núcleos (cada um com o intervalo proposto pela atribuição espectral). Com `qot-adaptive`, o resultado é o formato mais eficiente que respeita o QoT do novo circuito e dos já estabelecidos. Se nenhum for viável, a causa de bloqueio é a do formato mais robusto (a última falha específica).

A verificação dos circuitos ativos (passo iii) só reavalia os que compartilham um enlace com o candidato no mesmo núcleo (NLI e carga dos amplificadores) ou num núcleo adjacente (XT). O candidato não altera o ruído dos demais, então a decisão é a mesma de verificar todos, com custo menor.

---

## 3. Saída para Sucesso vs. Bloqueio

A chamada ao método `allocate` sempre retorna uma instância do record [AllocationResult](file:///Users/iallen/Dev/java/SNetS2/src/main/java/com/snets2/model/AllocationResult.java) contendo dados estruturados:

### 3.1. Saída em caso de Sucesso
Quando todos os filtros, restrições espectrais e validações de QoT física passam com sucesso, o objeto retornado contém:
* **`isBlocked`**: `false` (Indica alocação bem-sucedida).
* **`blockingCause`**: `null`.
* **`blockingCoreId`**: `null`.
* **`path`**: Lista de enlaces físico ([Link](file:///Users/iallen/Dev/java/SNetS2/src/main/java/com/snets2/model/Link.java)) que formam o caminho.
* **`coreIndices`**: Índices dos núcleos alocados para cada enlace do caminho.
* **`startSlot` / `endSlot`**: O intervalo espectral alocado (ex: slots de 12 a 16).
* **`modulation`**: Formato de modulação selecionado ([ModulationFormat](file:///Users/iallen/Dev/java/SNetS2/src/main/java/com/snets2/model/ModulationFormat.java)).
* **`regeneratorNodes`**: Lista de nós intermediários onde a regeneração óptica foi instalada (vazia caso o alcance direto seja suficiente).

### 3.2. Saída em caso de Bloqueio
Se todos os caminhos, modulações e núcleos forem testados e nenhum for viável, o algoritmo retorna um objeto de bloqueio com:
* **`isBlocked`**: `true` (Indica falha/bloqueio da requisição).
* **`blockingCause`**: Causa raiz identificada durante a varredura (instância do enum [BlockingCause](file:///Users/iallen/Dev/java/SNetS2/src/main/java/com/snets2/metrics/BlockingCause.java)):
  - `LACK_OF_TRANSMITTERS`: Falta de transmissores de hardware no nó de origem.
  - `LACK_OF_RECEIVERS`: Falta de receptores de hardware no nó de destino.
  - `NO_PATH`: Nenhum trajeto físico conecta a origem ao destino.
  - `FRAGMENTATION`: Faltou espectro contíguo nos núcleos avaliados.
  - `QOT_NEW`: A qualidade do sinal de transmissão (SNR) da nova conexão ficaria abaixo do aceitável.
  - `CROSSTALK`: A nova conexão sofre interferência inter-núcleo excessiva.
  - `QOT_OTHERS`: A ativação deste sinal degradaria conexões já ativas na rede.
  - `XT_OTHERS`: O crosstalk inter-núcleo gerado por esta conexão causaria queda de conexões vizinhas.
* **`blockingCoreId`**: O índice do núcleo (Core) associado à falha espectral ou física (quando aplicável).
* **Campos de Alocação (`path`, `coreIndices`, `modulation`)**: Inicializados como `null` ou valores nulos (ex: `-1`), pois nenhuma rota foi estabelecida.
