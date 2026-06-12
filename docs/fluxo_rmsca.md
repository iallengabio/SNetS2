# Fluxo do Algoritmo `StandardIntegratedRMSCA`

Este documento explica o fluxo detalhado de execução do algoritmo de RMSCA (Routing, Modulation, Spectrum, and Core Allocation) integrado em [StandardIntegratedRMSCA.java](file:///Users/iallen/Dev/java/SNetS2/src/main/java/com/snets2/rmsca/StandardIntegratedRMSCA.java), descrevendo a ordem de execução dos sub-algoritmos e os critérios de decisão para o sucesso ou bloqueio da requisição.

---

## 1. Ordem de Execução e Sub-Algoritmos

O algoritmo segue uma estratégia estruturada em etapas lógicas, executadas de forma sequencial e em laços aninhados:

1. **Checagem de Hardware de Nós**: Validação de transmissores (Tx) e receptores (Rx) disponíveis.
2. **Roteamento (IRouting)**: Busca de caminhos candidatos.
3. **Loop de Caminhos**: Avaliação individual de cada trajeto físico retornado pelo roteamento.
4. **Loop de Modulação (IModulationSelection)**: Varredura de formatos de modulação disponíveis, ordenados por eficiência espectral decrescente.
5. **Cálculo de Demanda Espectral**: Determinação do número de slots de espectro requeridos.
6. **Loop de Núcleos (ICoreAssignment)**: Varredura de núcleos candidatos do cabo de fibra multicore.
7. **Atribuição Espectral (ISpectrumAssignment)**: Busca de slots contíguos e contínuos livres.
8. **Atribuição de Regeneradores (IRegeneratorAssignment)**: Alocação de regeneradores se o alcance máximo for violado.
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
   └──> 4. Laço: Iterar sobre cada Caminho Candidato
           │
           └──> 5. Laço: Iterar sobre cada Modulação (ordenadas da mais eficiente para a menos eficiente)
                   │
                   ├──> a. Teste de Alcance Físico (Reach)
                   │       Se Distância > Alcance Máximo E sem Regenerador ativo ──> Pular Modulação
                   │
                   ├──> b. Cálculo do número de slots requeridos
                   │
                   └──> c. Laço: Iterar sobre cada Núcleo (Core) Candidato
                           │
                           ├──> i. Atribuição Espectral (ISpectrumAssignment)
                           │       Buscar slots contíguos e contínuos livres.
                           │       Se não encontrar ──> Pular Core (Causa temporária: FRAGMENTATION)
                           │
                           ├──> ii. Alocação de Regeneradores (se Distância > Alcance Máximo)
                           │         Tentar alocar nós intermediários com regeneração.
                           │         Se falhar ──> Pular Core
                           │
                           ├──> iii. Validação de QoT do Novo Canal (Se QoT ativo)
                           │          Calcular a SNR predita.
                           │          Se SNR < Limiar Mínimo:
                           │             - Tentar regeneradores adicionais para restaurar sinal.
                           │             - Se persistir a falha:
                           │                 Isolar a causa (CROSSTALK se passar sem XT, senão QOT_NEW).
                           │                 Pular Core
                           │
                           ├──> iv. Validação de QoT nos Canais Vizinhos (Se QoT ativo para outros)
                           │         Aplicar ruído temporário (NLI e XT) nas fibras adjacentes.
                           │         Calcular se alguma conexão ativa degrada abaixo do limiar.
                           │         Remover ruído temporário.
                           │         Se degradar ──> Isolar causa (XT_OTHERS ou QOT_OTHERS) e Pular Core
                           │
                           └──> v. SUCESSO DE ALOCAÇÃO
                                   Retorna imediatamente AllocationResult de sucesso.
```

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
