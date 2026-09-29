# SNetS2: Configuração de Experimentos (Experiment Setup)

## 1. Visão Geral
A entrada de dados do SNetS2 é centralizada e gerida por um único arquivo de configuração no formato JSON. A raiz deste arquivo é o objeto `experimentSetup`, que encapsula todos os parâmetros necessários para definir a topologia, características físicas, algoritmos, carga de tráfego e o planejamento de execução (bateria de testes).

Esta abordagem "Configuration-as-Code" permite total reprodutibilidade, fácil integração com pipelines automatizados e execução em lote (batch processing).

A estrutura principal do JSON é dividida em 5 chaves principais:
1. `networkTopology`
2. `physicalLayer`
3. `simulation`
4. `traffic`
5. `experimentalPlanning`

---

## 2. Topologia da Rede (`networkTopology`)
Define os elementos estruturais da rede, capacidades de roteamento e a geometria dos núcleos (cores) das fibras ópticas.

```json
"networkTopology": {
  "nodes": [
    {"id": "0", "tx": 100, "rx": 100, "regenerators": 10, "addDropDegree": 1},
    {"id": "1", "tx": 100, "rx": 100, "regenerators": 10, "addDropDegree": 1}
  ],
  "links": [
    {"source": "0", "destination": "1", "length": 100.0}
  ],
  "cores": [
    {"id": 0, "adjacentCores": [1, 2]},
    {"id": 1, "adjacentCores": [0, 2, 3]}
  ]
}
```
* **nodes:** Lista de nós ópticos (ROADM). Cada nó possui uma quantidade definida de transmissores (`tx`), receptores (`rx`) e `regenerators`.
  * `addDropDegree` (opcional, padrão `1`): grau de *add/drop* do ROADM (número de portas de inserção/extração), termo $a$ da potência do OXC $85\,n + 100\,a + 150$ W ([06_output_metrics.md](06_output_metrics.md), Seção 3.6). Não depende de `tx`/`rx`. Deve ser `>= 0`; o valor `0` em um nó com transceptores gera um aviso.
* **links:** Fibras bidirecionais (ou unidirecionais dependendo da modelagem interna, a definir) conectando os nós, com o comprimento (`length`) tipicamente em quilômetros.
* **cores:** A representação geométrica dos núcleos dentro da fibra. Em vez de forçar um modelo espacial estático, a lista de `adjacentCores` permite representar qualquer disposição (linear, anelar, hexagonal, etc.), sendo crucial para o cálculo de Crosstalk (XT).
* **modulations:** Lista de formatos de modulação disponíveis. Cada item define o nome, o alcance máximo estimado (`maxRange`), a ordem da modulação (`M`), o limiar de SNR (SNR) e a tolerância a Crosstalk (`XT`).
  * **`maxRange` (km):** alcance transparente usado pela seleção de modulação por distância. Nos experimentos do repositório ele é **derivado do modelo físico**, com o mesmo FEC dos limiares de SNR, por `scripts/compute_reach.sh <setup.json>`. A carga de referência é um enlace com o núcleo cheio de canais iguais (XCI dos vizinhos incluído), e a taxa de referência é o pior caso entre as de `traffic.bitRates`. Resultado: 4QAM 5110, 8QAM 3270, 16QAM 2000, 32QAM 400 e 64QAM 160 km. Procedimento e tabela completa em [07_physical_layer_models.md](07_physical_layer_models.md), §6.2. **Recalcule-o** ao mudar a potência, o FEC, a grade, os amplificadores, as taxas ou os limiares.

---

## 3. Camada Física (`physicalLayer`)
Contém todos os parâmetros fundamentais para a avaliação da Qualidade de Transmissão (QoT) e penalidades da camada física (PLIs).

```json
"physicalLayer": {
  "activeQoT": true,
  "activeQoTForOther": true,
  "activeASE": true,
  "activeNLI": true,
  "activeXT": true,
  "activeXTForOther": true,
  "rateOfFEC": 0.25,
  "power": 0.0,
  "spanLength": 80.0,
  "fiberLoss": 0.2,
  "fiberNonlinearity": 0.0013,
  "fiberDispersion": 1.6E-5,
  "centerFrequency": 1.9385E14,
  "constantOfPlanck": 6.626E-34,
  "noiseFigureOfOpticalAmplifier": 5.0,
  "powerSaturationOfOpticalAmplifier": 23.0,
  "noiseFactorModelParameterA1": 100.0,
  "noiseFactorModelParameterA2": 4.0,
  "typeOfAmplifierGain": 0,
  "amplificationFrequency": 1.9385E14,
  "switchInsertionLoss": 5.0,
  "fixedPowerSpectralDensity": false,
  "referenceBandwidthForPowerSpectralDensity": 1.25E10,
  "propagationConstant": 1.0E7,
  "bendingRadius": 0.01,
  "couplingCoefficient": 0.012,
  "corePitch": 4.5E-5,
  "polarizationModes": 2.0,
  "guardBand": 1,
  "bvtSpectralWidth": 12.5E9
}
```
* **Parâmetros Baseados em Componentes:** Perdas de fibra, não-linearidades, dispersão e parâmetros dos amplificadores ópticos.
* **Amplificadores e ROADM** (equações em [07_physical_layer_models.md](07_physical_layer_models.md), Seção 3):
  * `typeOfAmplifierGain`: `0` = ganho fixo ($G = G_0$); `1` = ganho saturado pela potência total do núcleo. Outros valores são rejeitados.
  * `powerSaturationOfOpticalAmplifier`: potência de saturação **de saída** $P_{sat}$ (dBm), em que o ganho cai 3 dB. A carga considerada é a potência total do núcleo (soma dos circuitos que atravessam o amplificador). Usada apenas com ganho saturado. **Valor dos experimentos: 23 dBm** (antes 16 dBm). EDFAs de linha comerciais para a banda C têm potência de saída total de ~20–23 dBm. Na grade de 320 slots, um núcleo cheio de canais de 0 dBm soma de 12 a 22 dBm e, com 16 dBm, 40 canais já estariam no ponto de 3 dB. Justificativa em [07_physical_layer_models.md](07_physical_layer_models.md), §3.3.
  * `noiseFactorModelParameterA1` / `noiseFactorModelParameterA2`: parâmetros $A_1$ (adimensional) e $A_2$ (W) do fator de ruído $F = NF(1 + A_1 - A_1/(1 + P_{in}/A_2))$. Usados apenas com ganho saturado. A unidade W de $A_2$ segue o SNetS v1 e **não foi confirmada** no artigo de Pereira et al. (2009) (ver §3.3 do documento 07).
  * `switchInsertionLoss`: perda de inserção (dB) de cada elemento do ROADM; o booster compensa demux + switch + mux ($G_0 = 3 L_{sss}$).
* **Potência de lançamento** (Seção 2 do mesmo documento):
  * `fixedPowerSpectralDensity`: `false` = todo circuito é lançado com `power`; `true` = todo circuito mantém a PSD `power / referenceBandwidthForPowerSpectralDensity`, e sua potência passa a ser proporcional à sua largura de banda de sinal (sem banda de guarda).
  * `referenceBandwidthForPowerSpectralDensity`: largura de banda de referência $B_{ref}$ (Hz). Deve ser > 0 quando a PSD é fixa.
* **Chaves do SNetS v1 não suportadas:** `physicalLayerModel`, `crosstalkModel` e `typeOfTestQoT` foram removidas (ver Seção 7 de [07_physical_layer_models.md](07_physical_layer_models.md)); um `setup.json` que as contenha é rejeitado pelo parser.
* **Parâmetros MC-EON / XT:** `propagationConstant`, `bendingRadius`, `couplingCoefficient`, `corePitch` são os coeficientes necessários para o cálculo matemático do Crosstalk estatístico entre núcleos.
* **Granularidade e Transceptores:** `guardBand` (número de slots vazios para evitar interferência adjacente) e `bvtSpectralWidth` (largura de um slot, em Hz).
* **Número de slots por requisição:** $n = \lceil R\,(1+\text{rateOfFEC}) / (\text{polarizationModes}\cdot\log_2 M\cdot f_{slot}) \rceil + \text{guardBand}$. Se `polarizationModes` não for informado (0), assume-se 1 polarização.
* **`rateOfFEC` — como preencher:** é o **overhead** do FEC, como **fração** da taxa útil. A taxa de linha é $R_{linha} = R\,(1 + \text{rateOfFEC})$.

  | FEC | `rateOfFEC` |
  | :-- | :-- |
  | HD-FEC 7 % (ITU-T G.975.1) | `0.07` |
  | SD-FEC 15 % | `0.15` |
  | SD-FEC 20 % | `0.20` |
  | SD-FEC 25 % | `0.25` |
  | sem FEC (padrão, se omitido) | `0` |

  * **Não** é a taxa de código $r$ (fração de bits úteis). Se o seu dado estiver como taxa de código, converta: $\text{rateOfFEC} = 1/r - 1$ (ex.: $r = 0{,}8 \Rightarrow 0{,}25$). Por isso `rateOfFEC: 0.8` significaria 80 % de overhead, e não um código de taxa 0,8.
  * **Coerência com os limiares:** o overhead aumenta o número de slots, enquanto o ganho do FEC aparece nos **limiares de SNR** (`SNR`) das modulações, que são limiares **pré-FEC**. Os dois devem descrever o **mesmo** código. Um SD-FEC de 20–25 % tolera BER pré-FEC da ordem de $2\cdot10^{-2}$, portanto limiares de SNR vários dB menores que os de um HD-FEC de 7 % (BER pré-FEC da ordem de $10^{-3}$–$4\cdot10^{-3}$). Usar o overhead de um SD-FEC com limiares de HD-FEC penaliza a configuração duas vezes: em espectro e em alcance. Os limiares dos experimentos do repositório seguem o SD-FEC (BER pré-FEC $2{,}4\cdot10^{-2}$). Para gerar os de outro FEC, use `scripts/compute_thresholds.py` (ver `07_physical_layer_models.md`, §6.1).

---

## 4. Parâmetros de Simulação (`simulation`)
Define as políticas lógicas, algoritmos ativados, e quais métricas devem ser coletadas durante a execução.

```json
"simulation": {
  "requests": 100000,
  "warmUpRequests": 5000,
  "totalSlots": 320,
  "routing": "newksp",
  "spectrumAssignment": "randomfit",
  "coreAndSpectrumAssignment": "mincrosstalkcore",
  "integratedRMSCA": "standard",
  "modulationSelection": "distance-adaptive",
  "regeneratorAssignment": "aar",
  "activeMetrics": {
    "BlockingProbability": true,
    "BitRateBlockingProbability": true,
    "SpectrumUtilization": true,
    "SpectrumSizeStatistics": false,
    "ExternalFragmentation": false,
    "RelativeFragmentation": false,
    "TransmittersReceiversRegeneratorsUtilization": false,
    "ModulationUtilization": true,
    "ConsumedEnergy": false,
    "CrosstalkStatistics": true,
    "SimulationMetadata": false
  }
}
```
* **requests:** Critério de parada primário da simulação (número total de requisições geradas).
* **warmUpRequests:** número de requisições iniciais descartadas das métricas (deve ser `< requests`). **totalSlots:** slots por núcleo.
* **Algoritmos (RMSCA):** IDs registrados na `AlgorithmFactory`:
  * `integratedRMSCA`: `standard`.
  * `routing`: `djk`, `ksp`/`newksp` (k = 3).
  * `modulationSelection`: `distance-adaptive` (padrão) ou `fixed`.
  * `coreAndSpectrumAssignment`: `firstfitcore`, `randomfitcore`, `mincrosstalkcore`/`mincrosstalk`.
  * `spectrumAssignment`: `firstfit`, `lastfit`/`lf`, `exactfit`/`ef`, `randomfit`, `dummyfit`.
  * `regeneratorAssignment`: `aar` (opcional).

  As chaves `kRouting`, `grooming`, `reallocation`, `powerAssignment` e `networkType` são aceitas, mas **ignoradas**; o simulador emite um aviso.
* **activeMetrics:** liga ou desliga cada métrica. Uma métrica **omitida é considerada ativa**. Desativar métricas complexas (ex.: fragmentação) melhora significativamente o desempenho. Nomes desconhecidos geram aviso.

---

## 5. Modelo de Tráfego (`traffic`)
Controla o gerador de eventos da simulação, definindo a carga e a distribuição das demandas de largura de banda.
```json
"traffic": {
  "loadDistributionPerPair": "uniform",
  "load": 1000,
  "bitRates": [
    {"value": 100.0, "weight": 1.0},
    {"value": 200.0, "weight": 0.5},
    {"value": 400.0, "weight": 0.25}
  ]
}
```
* **Carga (`load`):** carga **total** oferecida à rede, em Erlangs. É obrigatória. Os pares (origem, destino) são sorteados uniformemente (`loadDistributionPerPair: "uniform"`, a única opção suportada). `loadByPair` não é suportado e gera erro de validação.
* **bitRates:** Uma lista de objetos que definem as larguras de banda requisitadas.
    * `value`: A taxa de bits da requisição em Gbps.
    * `weight`: O peso estatístico desta largura de banda. A probabilidade de uma requisição ter um determinado `value` é dada por $P(v_i) = \frac{weight_i}{\sum weight}$. No exemplo acima, requisições de 100G são 2x mais prováveis que as de 200G e 4x mais prováveis que as de 400G.
* **Taxas do DES (Discrete Event Simulator):** A taxa de retenção das conexões (*hold rate*, $\mu$) é fixada matematicamente em 1. Logo, a taxa de chegada (*arrival rate*, $\lambda$) calculada pelo simulador para atingir a carga informada será diretamente $\lambda = \text{load} \times \mu$.

---

## 6. Planejamento Experimental (`experimentalPlanning`)
Responsável por automatizar a execução de múltiplas configurações sem necessidade de scripts externos. Este módulo realiza uma varredura de parâmetros (Parameter Sweep).

```json
"experimentalPlanning": {
  "traffic.load": [1000, 1200, 1400],
  "simulation.spectrumAssignment": ["firstFit", "randomFit", "bestFit"],
  "replications": 10
}
```
* **Notação Ponto (Dot Notation):** Chaves aninhadas do JSON raiz podem ser referenciadas por notação de ponto para criar variações de configuração.
* **Produto Cartesiano:** O simulador cria combinações de todas as listas. No exemplo acima, serão gerados 9 cenários únicos (3 cargas $\times$ 3 algoritmos de espectro).
* **Replications:** Para cada um dos 9 cenários, o simulador executará o experimento 10 vezes (provavelmente paralelizado via `simulation.threads`). Cada replicação utilizará uma **semente de aleatoriedade diferente**.
* **Estatísticas Finais:** Ao final de todas as replicações, o simulador agrega os resultados das métricas ativas e calcula a Média, Desvio Padrão e Intervalo de Confiança.

---

## 7. Validação da Configuração
Antes de executar qualquer replicação, o `ExperimentalPlanner` valida **todos** os cenários do *sweep* com o `ConfigValidator`.
* **Erros** (a execução é abortada com a lista completa):
  * `traffic.load` ausente ou ≤ 0; `loadByPair`; distribuição diferente de `uniform`.
  * Menos de 2 nós; nós ou enlaces duplicados, desconhecidos ou com comprimento ≤ 0; `tx`, `rx`, `regenerators` ou `addDropDegree` negativos.
  * Adjacência de núcleos assimétrica ou com núcleo inexistente.
  * Modulação com `M < 2` ou `maxRange ≤ 0`.
  * `warmUpRequests ≥ requests`.
  * IDs de algoritmo ausentes.
  * Parâmetros físicos obrigatórios para os efeitos ativados (ASE, NLI, XT).
* **Avisos:** chaves reservadas com valor diferente do padrão, nomes desconhecidos em `activeMetrics` e `addDropDegree = 0` em nó com transceptores.
