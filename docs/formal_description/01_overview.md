# SNetS2: Visão Geral e Requisitos

## 1. Introdução
O **Slice Network Simulator 2 (SNetS2)** é um simulador de eventos discretos (DES) especializado em redes ópticas elásticas multicore (MC-EON). Ele visa superar as limitações de sua primeira versão em termos de extensibilidade e performance, permitindo a simulação de cenários de SDM (Spatial Division Multiplexing) em larga escala.

## 2. Objetivos
- **Escalabilidade:** Simular redes com centenas de nós e milhares de requisições por segundo.
- **Fidelidade Física:** Implementar modelos precisos de degradação de sinal, especialmente Crosstalk Inter-core.
- **Modularidade:** Permitir a troca fácil de algoritmos de RMSCA (Routing, Modulation, Spectrum, and Core Allocation) sem alterar o núcleo do simulador.
- **Automação Experimental:** Eliminar a necessidade de scripts externos complexos para a execução de varreduras de parâmetros (parameter sweeps).

## 3. Paradigmas de Design e Desenvolvimento
Para garantir a modernidade e utilidade acadêmica do simulador, as seguintes premissas foram adotadas:

- **Configuration-as-Code (Single Source of Truth):** Toda a configuração da simulação, desde a topologia física até o planejamento de baterias de testes, é descrita em um único objeto JSON (`experimentSetup`).
- **Resiliência e Checkpointing:** Devido à natureza intensiva dos experimentos (que podem durar horas ou dias), o SNetS2 implementa um mecanismo de persistência de progresso. O estado do `experimentalPlanning` é salvo periodicamente, permitindo que, em caso de interrupção (falha de hardware, queda de energia), o simulador retome a execução a partir do último cenário/replicação não concluído, evitando o desperdício de tempo computacional.
- **Wide Data Format (Preservação da Variância):** Ao contrário de simuladores que exportam apenas médias, o SNetS2 exporta os resultados de *cada replicação individualmente* (`rep0`, `rep1`, ..., `repN`). Isso permite análises de variância, desvio padrão e intervalos de confiança robustos em ferramentas externas.
- **Integração Nativa com Data Science:** O formato de saída (Excel multi-abas ou diretório de CSVs) é otimizado para ingestão imediata por bibliotecas como Pandas, facilitando o uso de Seaborn e Matplotlib para visualização.
- **Modelagem de Carga em Erlangs:** O tráfego é parametrizado em Erlangs, com uma taxa de retenção ($\mu$) fixa em 1, simplificando a matemática de chegada de eventos no DES.

## 4. Escopo Técnico
- **Tecnologia de Rede:** EON com suporte a múltiplos núcleos (Cores) por fibra e ROADMs com arquitetura flexível.
- **Modelo de Simulação:** Discrete Event Simulation (DES) baseado em eventos de chegada e partida de conexões.
- **Entidades Principais:**
  - `Node`: Comutadores ópticos com contagem variável de transceptores e regeneradores.
  - `Link`: Fibras com múltiplos núcleos e grade de espectro flexível (slots).
  - `Core`: Geometria configurável via listas de adjacência (`adjacentCores`) para cálculo preciso de Crosstalk.
  - `Connection`: Requisições (Lightpaths) com banda, modulação e QoT dinâmica.

## 5. Fluxo de Trabalho (Workflow)
1. **Definição:** O usuário cria um JSON seguindo o esquema do `experimentSetup`.
2. **Execução:** O simulador interpreta o `experimentalPlanning`, realiza o produto cartesiano dos parâmetros variáveis e executa as replicações (paralelizadas de acordo com o hardware disponível).
3. **Análise:** O arquivo de saída consolidado é processado por scripts Python para geração de gráficos científicos.

## 6. Métricas Alvo
O simulador foca em quatro pilares de métricas, ativáveis sob demanda:
1. **Eficiência de Bloqueio:** Probabilidade de bloqueio de banda e requisição (geral e por causa raiz: QoT, fragmentação, falta de hardware).
2. **Utilização de Recursos:** Ocupação de slots de espectro, transmissores, receptores e regeneradores.
3. **Fisura Spectrale:** Índices de fragmentação externa e relativa.
6. **Qualidade de Transmissão:** Estatísticas detalhadas de Crosstalk e utilização de formatos de modulação. Veja os [Modelos da Camada Física](07_physical_layer_models.md) para detalhes matemáticos.
