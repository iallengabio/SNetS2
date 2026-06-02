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
* **Dimensões Adicionais:** `src` (nó de origem), `dest` (nó de destino).
* **SubMetrics Incluídas:**
  * General blocking probability
  * Blocking probability by lack of transmitters / receivers
  * Blocking probability by fragmentation
  * Blocking probability by QoTN / QoTO (Quality of Transmission New / Other)
  * Blocking probability by Crosstalk
  * Blocking probability per core `[ID]`
  * Blocking probability per pair `[src]-[dest]`

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

### 3.4. Aba: `CrosstalkStatistics`
Estatísticas da degradação de sinal por acoplamento de núcleos (específico para fibras Multicore).
* **Dimensões Adicionais:** `Overlaps` (número de conexões adjacentes).
* **SubMetrics Incluídas:**
  * Average Crosstalk per overlaps (dB)
  * Minimum Crosstalk per overlaps (dB)
  * Maximum Crosstalk per overlaps (dB)
  * Average Crosstalk per pair `[src]-[dest]`

### 3.5. Outras Abas
* **`SpectrumSizeStatistics`**: Distribuição do tamanho contíguo de espectro requerido.
* **`Fragmentation`** (`Relative` e `External`): Índices matemáticos de fragmentação espectral na rede.
* **`TransmittersReceiversRegeneratorsUtilization`**: Porcentagem de ocupação de hardware físico nos ROADMs.
* **`ConsumedEnergy`**: Estimativa de gasto energético (Watts) dos componentes ativos.
* **`GroomingStatistics`**: Estatísticas de empacotamento de tráfego (se ativado).

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
