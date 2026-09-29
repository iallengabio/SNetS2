# SNetS2: Formato de Saída e Métricas (Output & Metrics)

## 1. Visão Geral
A análise de desempenho do SNetS2 é delegada a scripts externos (ex: Python com Pandas, Matplotlib e Seaborn). Portanto, o papel do simulador é **coletar, organizar e exportar dados brutos** da forma mais eficiente e estruturada possível, garantindo que o pesquisador consiga cruzar informações e analisar a variância estocástica do sistema.

Para atender ao requisito de "um arquivo de planilha com uma aba para cada métrica", o simulador exportará os resultados no formato **Excel (`.xlsx` multi-abas)** ou em um **Diretório Estruturado contendo múltiplos `.csv`** (onde cada CSV atua logicamente como uma aba da planilha).

## 2. Estrutura Dinâmica de Colunas
No simulador antigo, as linhas eram identificadas por um `LoadPoint` abstrato. No SNetS2, como o módulo `experimentalPlanning` permite variar *qualquer* parâmetro (ex: algoritmo de alocação, carga, largura do BVTs), o cabeçalho da planilha se adapta dinamicamente.

Para suportar o requisito de **Checkpointing**, o simulador verifica a existência de resultados parciais antes de iniciar cada cenário. Se o arquivo de saída já contiver dados para uma replicação específica, o simulador a ignora e prossegue para a próxima pendente.

As colunas de todas as abas seguirão a seguinte arquitetura padrão:

| SubMetric | [Var 1] | [Var 2] | ... | [Dimensões] | rep0 | rep1 | ... | repN |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| (String) | (Dinâmico) | (Dinâmico) | ... | (Específico da Métrica) | (Float) | (Float) | ... | (Float) |

### Exemplo Teórico
Se o usuário configurou o JSON para variar `traffic.load` (100 e 200) e `simulation.spectrumAssignment` ("FF" e "RF"), com 3 replicações, a aba de **BlockingProbability** terá o seguinte aspecto:

| SubMetric | traffic.load | simulation.spectrumAssignment | src | dest | rep0 | rep1 | rep2 |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| General BP | 100 | FF | all | all | 0.05 | 0.04 | 0.06 |
| General BP | 100 | RF | all | all | 0.08 | 0.09 | 0.07 |
| General BP | 200 | FF | all | all | 0.15 | 0.14 | 0.16 |
| BP by QoT | 100 | FF | all | all | 0.01 | 0.01 | 0.02 |
| BP per pair | 100 | FF | 1 | 5 | 0.08 | 0.07 | 0.09 |

Desta forma, o script Python pode fazer facilmente operações como:
`df.groupby(['traffic.load', 'simulation.spectrumAssignment']).mean()`

---

## 3. Detalhamento das Abas (Métricas)

As abas geradas dependerão do bloco `"activeMetrics"` no JSON de configuração. Se uma métrica for definida como `false`, sua aba não será criada, economizando I/O e memória.

### 3.1. Aba: `BlockingProbability` (e `BitRateBlockingProbability`)
Avalia a probabilidade de rejeição de chamadas (ou rejeição ponderada por banda).
* **Dimensões Adicionais:** `src` (nó de origem), `dest` (nó de destino), `core` (núcleo) e `bitrate` (taxa de bits requisitada, em Gbps). Todas as linhas da aba têm as quatro dimensões; as que não se aplicam valem `all`.
* **SubMetrics Incluídas:**
  * General blocking probability
  * Blocking probability by lack of transmitters / receivers
  * Blocking probability by fragmentation
  * Blocking probability by QoTN / QoTO (Quality of Transmission New / Other)
  * Blocking probability by Crosstalk
  * Blocking probability per core `[ID]`
  * Blocking probability per pair `[src]-[dest]`
  * Blocking probability per bit rate `[bitrate]` (`BP per bit rate`): $BP_r = \frac{\text{taxa bloqueada das requisições de taxa } r}{\text{taxa requisitada das requisições de taxa } r}$, que coincide com a probabilidade de bloqueio de requisições da classe $r$

### 3.2. Aba: `SpectrumUtilization`
Avalia o quão cheio está o espectro da rede durante o estado estacionário da simulação.
* **Dimensões Adicionais:** `Link`, `Core`, `Slot`.
* **SubMetrics Incluídas:**
  * General Utilization
  * Utilization per Link `[ID]`
  * Utilization per Core `[ID]`
  * Utilization per Link and Core `[Link_ID]`_`[Core_ID]`
  * Utilization per Slot `[Slot_Index]`

### 3.3. Aba: `ModulationUtilization`
Monitora o uso de diferentes formatos de modulação pelos BVTs.
* **Dimensões Adicionais:** `Modulation` (ex: BPSK, QPSK, 16QAM), `Bandwidth`.
* **SubMetrics Incluídas:**
  * Percentage of circuits per modulation
  * Percentage of circuits per modulation and bandwidth

### 3.4. Aba: `PhysicalLayerStatistics`
Estatísticas da degradação de sinal e qualidade de transmissão.
* **Dimensões Adicionais:** `src`, `dest`, `overlaps` (número de slots vizinhos ocupados).
* **SubMetrics Incluídas:**
  * Average OSNR (dB)
  * Average XT (dB)
  * Average Power (dBm)
  * Min/Max XT per overlaps (dB)
  * Average OSNR/XT/Power per pair `[src]-[dest]`

### 3.5. Outras Abas
* **`SpectrumSizeStatistics`**: Distribuição do tamanho contíguo de espectro requerido.
* **`Fragmentation`** (`Relative` e `External`): Índices matemáticos de fragmentação espectral na rede.
* **`TransmittersReceiversRegeneratorsUtilization`**: Porcentagem de ocupação de hardware físico nos ROADMs.
* **`ConsumedEnergy`**: Potência média, estática e de pico da rede e energia consumida na janela medida (Seção 3.6).
* **`GroomingStatistics`**: Estatísticas de empacotamento de tráfego (se ativado).

### 3.6. Aba: `ConsumedEnergy` (modelo de energia)
O modelo de potência é o de Vizcaíno et al. [1]. Todas as constantes estão em `EnergyConsumptionModel`.

**Potência estática** (independe do tráfego):

$$P_{est} = \sum_{\text{enlaces}} N_{amp}\,P_{amp} + \sum_{\text{nós}} \left(85\,n + 100\,a + 150 + 80\,r\right)\ \text{W}$$

| Símbolo | Significado | Valor / origem |
| :-- | :-- | :-- |
| $P_{amp}$ | potência de um amplificador (EDFA) | 100 W (premissa do simulador, ver nota) |
| $N_{amp}$ | amplificadores do enlace: booster + $N_l$ de linha + pré-amplificador | $N_l + 2$, $N_l = \lceil L/L_{span} - 1\rceil$ (a mesma cadeia do modelo de ASE, [07_physical_layer_models.md](07_physical_layer_models.md), Seção 3.1) |
| $85\,n$ | OXC: 85 W por grau do nó | $n$ = nº de enlaces direcionados incidentes no nó (entrada + saída) |
| $100\,a$ | OXC: 100 W por porta de *add/drop* | $a$ = `addDropDegree` do nó (padrão 1). **Não** é o nº de transceptores instalados (`tx`/`rx`), cuja potência é dinâmica |
| $150$ | OXC: parcela fixa | 150 W [1] |
| $80\,r$ | regeneradores instalados, ociosos ou não | 80 W por regenerador (premissa do simulador, ver nota) |

**Potência de um circuito** (dinâmica, enquanto o circuito está ativo). Cada transponder segue o modelo linear de [1], com $TR = f_{slot}\log_2 M/10^9$ Gbps por slot:

$$P_{tran} = n_{slots}\cdot 1{,}683\,TR + 91{,}333\ \text{W}, \qquad P_{circ} = 2\,(1 + n_{reg})\,P_{tran}$$

(um transponder na origem e um no destino, e mais dois por regeneração).

> **Nota.** A potência do OXC ($85\,n + 100\,a + 150$) e a do transponder ($1{,}683\,TR + 91{,}333$) seguem [1]. Os valores de 100 W por EDFA e 80 W por regenerador instalado são premissas herdadas do código e ainda não foram conferidas com uma referência; ao usá-los em publicações, cite a fonte adotada.

**Janela medida e médias.** A potência da rede é a função em degraus $P(t) = P_{est} + \sum_{\text{ativos}} P_{circ}$, que só muda nos instantes de *setup* e *teardown*. Sendo $T_w$ o instante da chegada que encerra o *warm-up* (0 sem *warm-up*) e $T$ o tempo final:

$$E = \int_{T_w}^{T} P(t)\,dt, \qquad \bar P = \frac{E}{T - T_w}, \qquad P_{pico} = \max_{t \in [T_w, T]} P(t).$$

A integral é exata: o intervalo entre $T_w$ e o primeiro *setup*/*teardown* seguinte é integrado com a potência vigente em $T_w$. Pela lei de Little, em regime $\bar P = P_{est} + \bar P_{circ}\,\bar N_{ativos}$ (verificado em `EnergyModelTest` e no experimento E6 do relatório de V&V).

**SubMetrics:** `Average Total Power (W)` ($\bar P$), `Static Network Power (W)` ($P_{est}$), `Peak Network Power (W)` ($P_{pico}$), `Total Energy (J)` ($E$).

> [1] J. L. Vizcaíno, Y. Ye, I. Tafur Monroy, "Energy efficiency analysis for flexible-grid OFDM-based optical networks", *Computer Networks*, vol. 56, no. 10, 2012.

---

## 4. Importação e Análise em Python (Integração)

Ao padronizar os arquivos com identificadores dinâmicos, o pesquisador precisará apenas de algumas linhas de código em Python (Pandas) para plotar gráficos de linha com intervalos de confiança.

```python
import pandas as pd
import seaborn as sns
import matplotlib.pyplot as plt

# 1. Carrega a aba específica do Excel
df = pd.read_excel("SNetS2_Results.xlsx", sheet_name="BlockingProbability")

# 2. Filtra a métrica desejada
df_bp = df[(df['SubMetric'] == 'General blocking probability') & 
           (df['src'] == 'all')]

# 3. Derrete (Melt) as colunas de replicações para análise estatística
# Isso transforma rep0, rep1.. repN em linhas, permitindo que o seaborn calcule
# o intervalo de confiança automaticamente.
rep_columns = [col for col in df_bp.columns if col.startswith('rep')]
id_vars = [col for col in df_bp.columns if col not in rep_columns]

df_melted = df_bp.melt(id_vars=id_vars, value_vars=rep_columns, 
                       var_name='Replication', value_name='Blocking Prob')

# 4. Plota o gráfico (Carga vs Bloqueio), separando as linhas por Algoritmo
sns.lineplot(data=df_melted, x='traffic.load', y='Blocking Prob', 
             hue='simulation.spectrumAssignment', marker='o')

plt.yscale('log')
plt.show()
```
Esta abordagem elimina a necessidade de calcular médias manualmente no simulador e aproveita o poder estatístico direto das ferramentas modernas de análise de dados.
