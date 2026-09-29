# Arquitetura Otimizada: Incremental State Caching

Este documento detalha a estratégia de engenharia de software adotada no SNetS2 para garantir que os cálculos de OSNR e Crosstalk não se tornem o gargalo de desempenho da simulação.

## 1. O Problema da Complexidade O(N²)
No SNetS v1 ([alexandrefontinele/SNetS-SDM-SBRC26](https://github.com/alexandrefontinele/SNetS-SDM-SBRC26)), cada vez que um algoritmo RMSCA precisava avaliar a viabilidade de um caminho, ele iterava sobre todos os circuitos ativos na rede para somar as interferências. À medida que o tráfego cresce, o número de verificações cresce quadraticamente, tornando as simulações inviáveis para redes de grande escala.

## 2. A Solução: Cache de Estado por Slot
O SNetS2 introduz o conceito de **Physical State Cache**. Em vez de recalcular tudo sob demanda, o estado físico da rede é mantido de forma incremental dentro das entidades do modelo (`Link` e `Core`).

### Estruturas de Dados
Cada objeto `Core` em cada `Link` mantém os seguintes campos:
- `double[] nliNoiseCache`: armazena, por slot, a soma dos termos de **XCI** dos canais ativos do núcleo, $\sum_j N_{vãos}\,\mu\,G_j^2\ln\frac{|\Delta f|+B_j/2}{|\Delta f|-B_j/2}$ (adimensional; multiplicada pela PSD $G_i$ da vítima na predição, resulta em W/Hz). Um canal não escreve nos próprios slots; o seu SCI é somado analiticamente na predição. Ver `formal_description/07`.
- `double[] xtNoiseCache`: armazena a densidade de ruído de crosstalk acumulada em cada slot (W/Hz): $\sum_j P_j h L / B_j$ dos circuitos em núcleos adjacentes. A razão de XT da vítima é $\sum_{enlaces}\text{avg}(I_{XT})/I_{ch}$ (`PhysicalLayerModel.predictXtRatio`), comparada ao limiar de XT da modulação.
- `double totalLaunchPower` (W): soma das potências de lançamento dos circuitos ativos no núcleo (`addLaunchPower`/`removeLaunchPower`), usada pelo modelo de ganho saturado dos amplificadores.

Métodos auxiliares como `addNliNoise`, `removeNliNoise` e `getAverageNliNoise` garantem a manipulação segura e eficiente destes caches.

### O Ruído ASE
- **Ganho fixo** (`typeOfAmplifierGain = 0`): o ASE depende apenas da infraestrutura (booster, amplificadores de linha e pré-amplificador), e não da carga. Cada `Link` mantém o valor pré-calculado `staticAseNoise`, inicializado pelo `ControlPlane` no início da simulação via `PhysicalLayerModel.calculateLinkAse`.
- **Ganho saturado** (`typeOfAmplifierGain = 1`): o ASE depende da carga do núcleo e é recalculado na predição a partir de `Core.totalLaunchPower`. Se o intervalo de slots avaliado ainda está livre (circuito candidato), a potência do candidato é somada à carga; se já está ocupado (circuito ativo), ela já está contabilizada. O custo é $O(N_{amp})$ por enlace, sem dependência do número de circuitos ativos.

## 3. Funcionamento Incremental (Reativo)
A computação pesada é deslocada da fase de **Predição** (que ocorre milhares de vezes por segundo) para a fase de **Mutação** (que ocorre apenas no setup/teardown).

### Ao Estabelecer um Circuito (`SetupEvent`)
Toda a atualização física é feita por `ControlPlane.applyPhysicalContribution(circuit, add)`, também usado pelo `StandardIntegratedRMSCA` para aplicar temporariamente o circuito candidato durante a verificação de QoT dos outros circuitos (QoTO).
1. A potência de lançamento do circuito (`PhysicalLayerModel.circuitLaunchPower`, que respeita `fixedPowerSpectralDensity`) é somada ao `totalLaunchPower` do núcleo em cada enlace.
2. O `ControlPlane` invoca o `PhysicalLayerModel` para gerar as máscaras de interferência.
3. **NLI:** o método `generateNliMask` calcula o termo de XCI que o novo circuito injeta em todos os slots do mesmo núcleo fora da sua própria banda (forma GN $\ln((|\Delta f|+B/2)/(|\Delta f|-B/2))$, por vão).
4. **XT:** O método `calculateXtContribution` calcula o ruído que será injetado nos núcleos adjacentes exatamente nos mesmos slots ocupados.
5. O `ControlPlane` atualiza os caches dos objetos `Core` afetados.

### Ao Remover um Circuito (`TeardownEvent`)
1. O simulador realiza as mesmas chamadas e subtrai os valores dos arrays de cache, garantindo que o sistema retorne ao estado limpo (consistência validada via testes unitários).

## 4. Predição Ultra-Rápida ($O(S)$)
Quando um algoritmo RMSCA (ex: `StandardIntegratedRMSCA`) precisa validar um intervalo de slots `[s1, s2]`:
1. Ele chama `PhysicalLayerModel.predictSNR`.
2. O motor consulta os caches e calcula o SNR linear: $SNR = I_{ch} / \sum_{enlaces}(I_{ASE} + I_{ch}\cdot\text{avg}(C_{XCI}) + I_{SCI}(B) + \text{avg}(I_{XT}))$, com $I_{ch} = P/B$ (P dado por `circuitLaunchPower`) e $I_{ASE}$ estático (ganho fixo) ou recalculado a partir da carga do núcleo (ganho saturado).
3. O custo computacional depende apenas do número de slots da requisição ($S$), e **não** do número total de conexões na rede.

## 5. Resumo de Ganhos
| Atividade | Complexidade SNetS1 | Complexidade SNetS2 |
| :--- | :--- | :--- |
| Predição de QoT | $O(\text{Circuitos Ativos})$ | $O(\text{Slots Requisitados})$ |
| Setup de Conexão | $O(1)$ | $O(\text{Grade Espectral})$ |
| Teardown de Conexão | $O(1)$ | $O(\text{Grade Espectral})$ |

Esta arquitetura permite que o SNetS2 escale para milhares de requisições simultâneas mantendo um tempo de execução previsível e baixo.

## 6. Ferramenta de alcance (`ReachCalculator`)
`com.snets2.verification.ReachCalculator` (escopo de teste; wrapper `scripts/compute_reach.sh`) calcula o `maxRange` de cada modulação de um `setup.json` com o próprio motor acima. Não há modelo paralelo:
1. Monta um `ControlPlane` com um único enlace de comprimento $L$ e um núcleo de `totalSlots` slots.
2. Estabelece (`establishCircuit`) canais iguais ao de teste em todas as posições contíguas da grade, exceto a central, o que preenche os caches de XCI e a carga do núcleo.
3. Avalia o canal central com `PhysicalLayerModel.predictSNR`.
4. Faz a bisseção sobre $L$ (múltiplos de 10 km) até o limiar `SNR` da modulação, para cada taxa de `traffic.bitRates`, e toma o menor alcance.

A carga de referência, a escolha da taxa e a tabela resultante estão em `docs/formal_description/07_physical_layer_models.md`, §6.2. `ReachCalculatorTest` verifica a monotonicidade do alcance com $M$, a bisseção (limiar atendido em $L$ e violado em $L + 10$ km) e que nenhum `experiments/*/setup.json` tem `maxRange` acima do alcance calculado.
