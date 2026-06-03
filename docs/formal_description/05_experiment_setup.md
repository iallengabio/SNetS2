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
    {"id": "0", "tx": 100, "rx": 100, "regenerators": 10},
    {"id": "1", "tx": 100, "rx": 100, "regenerators": 10}
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
* **links:** Fibras bidirecionais (ou unidirecionais dependendo da modelagem interna, a definir) conectando os nós, com o comprimento (`length`) tipicamente em quilômetros.
* **cores:** A representação geométrica dos núcleos dentro da fibra. Em vez de forçar um modelo espacial estático, a lista de `adjacentCores` permite representar qualquer disposição (linear, anelar, hexagonal, etc.), sendo crucial para o cálculo de Crosstalk (XT).
* **modulations:** Lista de formatos de modulação disponíveis. Cada item define o nome, o alcance máximo estimado (`maxRange`), a ordem da modulação (`M`), o limiar de SNR (SNR) e a tolerância a Crosstalk (`XT`).

---

## 3. Camada Física (`physicalLayer`)
Contém todos os parâmetros fundamentais para a avaliação da Qualidade de Transmissão (QoT) e penalidades da camada física (PLIs).

```json
"physicalLayer": {
  "physicalLayerModel": 0,
  "crosstalkModel": 0,
  "activeQoT": true,
  "activeQoTForOther": true,
  "activeASE": true,
  "activeNLI": true,
  "activeXT": true,
  "activeXTForOther": true,
  "rateOfFEC": 0.25,
  "typeOfTestQoT": 0,
  "power": 0.0,
  "spanLength": 80.0,
  "fiberLoss": 0.2,
  "fiberNonlinearity": 0.0013,
  "fiberDispersion": 1.6E-5,
  "centerFrequency": 1.9385E14,
  "constantOfPlanck": 6.626E-34,
  "noiseFigureOfOpticalAmplifier": 5.0,
  "powerSaturationOfOpticalAmplifier": 16.0,
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
* **Parâmetros MC-EON / XT:** `propagationConstant`, `bendingRadius`, `couplingCoefficient`, `corePitch` são os coeficientes necessários para o cálculo matemático do Crosstalk estatístico entre núcleos.
* **Granularidade e Transceptores:** `guardBand` (número de slots vazios para evitar interferência adjacente) e `bvtSpectralWidth` (amplitude espectral ocupada por um slot do Bandwidth Variable Transceiver).

---

## 4. Parâmetros de Simulação (`simulation`)
Define as políticas lógicas, algoritmos ativados, e quais métricas devem ser coletadas durante a execução.

```json
"simulation": {
  "requests": 100000,
  "routing": "djk",
  "kRouting": "newksp",
  "spectrumAssignment": "randomfit",
  "coreAndSpectrumAssignment": "csbasdm",
  "integratedRMSCA": "standard",
  "modulationSelection": "modulationbyqotv2",
  "reallocation": "fsalfav1",
  "powerAssignment": "apamem",
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
    "GroomingStatistics": false,
    "DataSetInformation": false,
    "CrosstalkStatistics": true
  }
}
```
* **requests:** Critério de parada primário da simulação (número total de requisições geradas).
* **Algoritmos (RMSCA):** Strings que identificam as heurísticas que serão instanciadas via Reflection/Factory Pattern no simulador.
* **activeMetrics:** Sistema de *opt-in* para métricas. Desativar métricas complexas (ex: fragmentação) pode melhorar significativamente a performance da simulação.

---

## 5. Modelo de Tráfego (`traffic`)
Controla o gerador de eventos da simulação, definindo a carga e a distribuição das demandas de largura de banda.
```json
"traffic": {
  "loadDistributionPerPair": "uniform",
  "load": 1000,
  "bitRates": [
```
    {"value": 100.0, "weight": 1.0},
    {"value": 200.0, "weight": 0.5},
    {"value": 400.0, "weight": 0.25}
  ]
}
```
* **Carga (Load):** O usuário deve definir *obrigatoriamente apenas um* dentre `load` ou `loadByPair`. Ambos são valores em Erlangs.
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
