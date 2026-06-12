# Detalhamento Técnico: Sistema de Métricas e Observação

Este documento descreve a arquitetura de coleta, processamento e exportação de métricas do SNetS2, garantindo precisão científica e suporte a grandes volumes de dados.

## 1. Arquitetura de Coleta

O SNetS2 separa a **captura** de dados (Eventos) do **armazenamento** (Classes de Métricas) e do **cálculo** (Exportadores).

### 1.1. `MetricsManager` (com.snets2.metrics.MetricsManager)
É o ponto central de acesso a todos os módulos de métricas.
- **Ciclo de Vida:** Uma nova instância é criada pelo `SimulationEngine` para cada replicação do experimento, garantindo que os contadores sejam resetados.
- **Módulos:** Contém instâncias de `BitRateBlockingMetrics` e `ResourceUtilizationMetrics`.

### 1.2. Classes de Armazenamento
- **`BitRateBlockingMetrics`**: Armazena somas brutas de bit rate (solicitado vs bloqueado). Utiliza `HashMap` para realizar breakdowns por par de nós, por core (cobrindo todos os cores disponíveis na topologia, mesmo aqueles com probabilidade de bloqueio zero) e por causas raízes detalhadas:
  - *BP by Fragmentation*: Bloqueio devido à fragmentação de slots espectrais.
  - *BP by Lack of Tx / Lack of Rx*: Falta de transmissores ou receptores disponíveis nos nós.
  - *BP by QoT New*: Falta de qualidade de transmissão (QoT) para o circuito entrante.
  - *BP by QoT Others*: Degradação inaceitável induzida pelo circuito entrante em circuitos já ativos na rede.
  - *BP by Crosstalk*: Crosstalk inter-núcleo excessivo para o circuito entrante.
  - *BP by Crosstalk Others*: Degradação inaceitável por crosstalk inter-núcleo induzida em conexões ativas na rede.
- **`ResourceUtilizationMetrics`**: Implementa a técnica de **Média Ponderada pelo Tempo**. Armazena acumuladores de ocupação multiplicados pelo tempo de permanência naquele estado ($\Delta t$).
- **`PhysicalLayerMetrics`**: Coleta estatísticas de qualidade de sinal (OSNR, XT, Potência) no momento do estabelecimento dos circuitos. Realiza breakdowns por par de nós e por contagem de sobreposições (*overlaps*).
- **`SimulationMetadataMetrics`**: Coleta metadados gerais da simulação, como o tempo total simulado, a duração média das requisições (geral e por bit rate), a quantidade média de conexões ativas na rede (usando média ponderada pelo tempo) e o número de conexões ativas amostradas em 10 intervalos ao longo do tempo.

### 1.3. Otimização de Desempenho e Coleta Condicional
Para evitar o consumo desnecessário de CPU e memória em simulações de grande escala, o `SimulationEngine` consulta o mapa `activeMetrics` (definido no arquivo `setup.json`). Se uma métrica estiver configurada como `false` (inativa), o motor de simulação e os eventos correspondentes ignoram o processamento e a coleta de dados associados. Métricas omitidas ou não configuradas no mapa padrão são consideradas ativas (`true`) por padrão para retrocompatibilidade.

---

## 2. Eventos de Observação (`...ObservationEvent`)

Seguindo o princípio de organização do projeto, **classes de sistema e de motor nunca chamam métricas diretamente**. Toda coleta é mediada por eventos de observação.

### 2.1. `ResourceUtilizationObservationEvent`
- **Gatilho:** Disparado pelo `SetupEvent` e `TeardownEvent` sempre que o estado da rede muda.
- **Lógica:** Calcula o tempo decorrido desde a última mudança e pesa o estado anterior da rede no acumulador global.
- **Precisão:** Este método garante que a métrica de utilização seja insensível ao "acaso" de observações periódicas, refletindo a ocupação exata da linha do tempo.

---

## 3. Formatação e Saída de Dados

O SNetS2 segue o paradigma de **Wide Data Format** descrito em [06_output_metrics.md](../formal_description/06_output_metrics.md).

### 3.1. Processamento de Resultados
Ao final de uma simulação, as classes de métricas não fornecem apenas médias, mas sim os valores agregados que serão formatados pelo exportador:
1.  **Dimensões Dinâmicas:** As métricas são organizadas por sub-métricas (ex: "BP by QoT") e dimensões (ex: "src", "dest").
2.  **Múltiplas Replicações:** O exportador coleta os resultados de `rep0`, `rep1`, ..., `repN` e os dispõe em colunas paralelas para análise de variância posterior em ferramentas como Pandas.

### 3.2. Estrutura de Planilha
Os dados são preparados para o **Excel Multi-abas**, onde cada aba corresponde a um módulo de métricas:
- Aba `BlockingProbability`: Bloqueio de chamadas e bit rate.
- Aba `SpectrumUtilization`: Utilização ponderada por link, core e slot.
- Aba `ExternalFragmentation`: Fragmentação externa vertical (média dos links e entropia de Shannon ponderadas no tempo) e horizontal (média no estabelecimento de caminhos).
- Aba `RelativeFragmentation`: Fração ponderada no tempo de espectro livre inutilizável para diferentes tamanhos de demanda $c$.
- Aba `ModulationUtilization`: Percentual de circuitos utilizando cada formato de modulação, geral e por taxa de transmissão.
- Aba `SpectrumSizeStatistics`: Distribuição percentual do número de slots alocados, geral e por link.
- Aba `TransmittersReceiversRegeneratorsUtilization`: Utilização média (ponderada no tempo) e de pico dos transmissores, receptores e regeneradores, geral e por nó.
- Aba `SimulationMetadata`: Exibe metadados gerais da simulação (tempo simulado total, duração média das requisições geral e por bit rate, quantidade média de conexões ativas na rede e a quantidade de conexões ativas ao longo do tempo de 10% a 100%).

### 3.3. Ordenação Natural de Linhas
Para evitar que colunas de dimensões numéricas (como `slots`, `overlaps`, `c`, `core`, etc.) sejam exibidas em ordem alfabética confusa (por exemplo: `12, 14, 17, 23, 3, 33, 4`...), o `ExcelExporter` implementa um comparador de **Ordenação Natural** (*natural sort*).

Este comparador analisa chaves e valores dos mapas de cenários e dimensões:
1. Se ambos os valores puderem ser interpretados como números reais (`Double`), eles são comparados numericamente.
2. Se forem cadeias de caracteres alfanuméricas mistas (como `core_2` e `core_10`), a comparação é segmentada em blocos numéricos e textuais de forma sequencial.
3. Isso garante que a apresentação dos dados nas planilhas do Excel siga a ordem lógica numérica intuitiva (ex: `0`, `1`, `2`, `10` e `3`, `4`, `9`, `12`, `14`, `33`).

