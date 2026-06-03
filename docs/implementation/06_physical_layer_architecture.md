# Arquitetura Otimizada: Incremental State Caching

Este documento detalha a estratégia de engenharia de software adotada no SNetS2 para garantir que os cálculos de OSNR e Crosstalk não se tornem o gargalo de desempenho da simulação.

## 1. O Problema da Complexidade O(N²)
No SNetS1, cada vez que um algoritmo RMSCA precisava avaliar a viabilidade de um caminho, ele iterava sobre todos os circuitos ativos na rede para somar as interferências. À medida que o tráfego cresce, o número de verificações cresce quadraticamente, tornando as simulações inviáveis para redes de grande escala.

## 2. A Solução: Cache de Estado por Slot
O SNetS2 introduz o conceito de **Physical State Cache**. Em vez de recalcular tudo sob demanda, o estado físico da rede é mantido de forma incremental dentro das entidades do modelo (`Link` e `Core`).

### Estruturas de Dados
Cada objeto `Core` em cada `Link` mantém os seguintes arrays:
- `double[] nliNoiseCache`: Armazena a densidade de ruído NLI acumulada em cada slot específico.
- `double[] xtNoiseCache`: Armazena a densidade de ruído de Crosstalk acumulada em cada slot específico.

Métodos auxiliares como `addNliNoise`, `removeNliNoise` e `getAverageNliNoise` garantem a manipulação segura e eficiente destes caches.

### O Ruído ASE (Constante por Enlace)
Como o ruído ASE depende apenas da infraestrutura (amplificadores) e não da carga de tráfego, cada `Link` mantém um valor pré-calculado `staticAseNoise`. Este valor é inicializado pelo `ControlPlane` no início da simulação usando o motor `PhysicalLayerModel`.

## 3. Funcionamento Incremental (Reativo)
A computação pesada é deslocada da fase de **Predição** (que ocorre milhares de vezes por segundo) para a fase de **Mutação** (que ocorre apenas no setup/teardown).

### Ao Estabelecer um Circuito (`SetupEvent`)
1. O `ControlPlane` invoca o `PhysicalLayerModel` para gerar as máscaras de interferência.
2. **NLI:** O método `generateNliMask` calcula a contribuição do novo circuito para todos os slots do mesmo núcleo (incluindo o decaimento logarítmico com a distância de frequência).
3. **XT:** O método `calculateXtContribution` calcula o ruído que será injetado nos núcleos adjacentes exatamente nos mesmos slots ocupados.
4. O `ControlPlane` atualiza os caches dos objetos `Core` afetados.

### Ao Remover um Circuito (`TeardownEvent`)
1. O simulador realiza as mesmas chamadas e subtrai os valores dos arrays de cache, garantindo que o sistema retorne ao estado limpo (consistência validada via testes unitários).

## 4. Predição Ultra-Rápida ($O(S)$)
Quando um algoritmo RMSCA (ex: `StandardIntegratedRMSCA`) precisa validar um intervalo de slots `[s1, s2]`:
1. Ele chama `PhysicalLayerModel.predictSNR`.
2. O motor consulta os caches e calcula o SNR linear: $SNR = I_{ch} / (I_{ASE} + \text{avg}(I_{NLI}) + \text{avg}(I_{XT}))$.
3. O custo computacional depende apenas do número de slots da requisição ($S$), e **não** do número total de conexões na rede.

## 5. Resumo de Ganhos
| Atividade | Complexidade SNetS1 | Complexidade SNetS2 |
| :--- | :--- | :--- |
| Predição de QoT | $O(\text{Circuitos Ativos})$ | $O(\text{Slots Requisitados})$ |
| Setup de Conexão | $O(1)$ | $O(\text{Grade Espectral})$ |
| Teardown de Conexão | $O(1)$ | $O(\text{Grade Espectral})$ |

Esta arquitetura permite que o SNetS2 escale para milhares de requisições simultâneas mantendo um tempo de execução previsível e baixo.
