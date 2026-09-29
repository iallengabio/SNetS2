# SNetS2: Algoritmos de RMSCA (Routing, Modulation, Spectrum, and Core Allocation)

## 1. Visão Geral
A modularidade é um dos pilares do SNetS2. Para permitir que pesquisadores testem novas heurísticas sem modificar o núcleo do simulador, o processo de alocação de recursos é decomposto em quatro sub-problemas principais, coletivamente chamados de **RMSCA**.

O Plano de Controle utiliza um **Padrão de Fábrica (Factory Pattern)** e **Reflexão** para instanciar os algoritmos definidos no arquivo de configuração JSON (`simulation` block).

---

## 2. Arquitetura Modular
Os algoritmos de RMSCA podem ser implementados de duas formas:
1.  **Abordagem Desacoplada:** Quatro algoritmos independentes que são chamados em sequência pelo Plano de Controle.
2.  **Abordagem Integrada:** Um único algoritmo que resolve todos os sub-problemas simultaneamente (muito comum para técnicas de Cross-Layer Optimization).

### 2.1. Interfaces de Algoritmos (Contratos)
Para garantir a interoperabilidade, cada tipo de algoritmo deve implementar uma interface específica:

*   **`IRouting`**: Recebe origem/destino e retorna uma lista de caminhos candidatos (`Path`).
*   **`ICoreAssignment`**: Recebe um caminho e retorna o índice do núcleo (`Core`) a ser utilizado.
*   **`IModulationSelection`**: Recebe um caminho e requisitos de banda, retornando o formato de modulação e o número de slots necessários.
*   **`ISpectrumAssignment`**: Recebe o caminho, o núcleo e a quantidade de slots, retornando os índices de início/fim dos slots (`SpectrumInterval`).
*   **`IRMSCA`**: Interface única que recebe a requisição completa e retorna um objeto `AllocationResult` (indicando o sucesso da alocação ou contendo os detalhes do bloqueio).

---

## 3. Fluxo de Decisão do RMSCA

O fluxo padrão executado pelo Plano de Controle ao receber uma `ArrivalEvent` é:

1.  **Cálculo de Caminhos:** O algoritmo de *Routing* gera $k$ caminhos.
2.  **Loop de Tentativas** em duas passadas. A primeira é transparente. A segunda usa regeneradores e só roda se houver `regeneratorAssignment` e a primeira falhar. Em cada passada, para cada caminho candidato:
    a. **Seleção de Modulação:** a política configurada fornece a lista ordenada de formatos. Na passada transparente, só entram os que alcançam o destino (`comprimento ≤ maxRange`), exceto quando a política dispensa o alcance (`qot-adaptive`, ver §3.1).
    b. **Atribuição de Core:** a estratégia fornece a lista ordenada de núcleos.
    c. **Alocação Espectral:** tenta encontrar slots contíguos e contínuos no núcleo/caminho escolhido.
    d. **Validação de QoT:** SNR (ASE + NLI + XT) e XT do novo circuito contra os limiares da sua modulação; em seguida, SNR e XT dos circuitos ativos afetados pelo candidato, com a sua interferência aplicada.
3.  **Resultado:** Se uma combinação válida for encontrada, o circuito é agendado. Caso contrário, a requisição é bloqueada.

A precedência é **caminho → formato → núcleo**: para cada caminho, o formato preferido é tentado em todos os núcleos (cada um com o intervalo proposto pela atribuição espectral) antes do formato seguinte, e o primeiro candidato viável é aceito.

### 3.1. Seleção de modulação por QoT (`qot-adaptive`)
Seja $\mathcal{M}$ o conjunto de formatos em ordem decrescente de $\log_2 M$. Para um caminho $p$, a política escolhe o primeiro $m \in \mathcal{M}$ para o qual existe um par (núcleo $c$, intervalo $I$) com

$$SNR_{novo} \ge SNR_{th}(m), \quad XT_{novo} \le XT_{th}(m), \quad SNR_k \ge SNR_{th}(m_k), \quad XT_k \le XT_{th}(m_k) \quad \forall\, k \in \mathcal{A}(p, c).$$

Os núcleos $c$ são percorridos na ordem da estratégia de núcleo, e $I$ é o intervalo de $n(m)$ slots proposto pela atribuição espectral em $c$. As grandezas dos circuitos $k$ são avaliadas com o candidato aplicado. As condições de XT só valem com `activeXT` (novo circuito) e `activeXTForOther` (estabelecidos). O `maxRange` não participa da decisão.

* **Circuitos verificados:** $\mathcal{A}(p, c)$ contém os circuitos ativos que usam algum enlace de $p$ no núcleo $c$ (NLI e carga dos amplificadores) ou num núcleo adjacente a $c$ (XT). O candidato não altera o ruído dos demais, então o resultado é igual ao da verificação de todos os ativos, desde que estes já atendam aos limiares (o que a própria verificação garante). O custo por candidato cai de $O(N_{ativos})$ previsões de SNR para $O(|\mathcal{A}(p, c)|)$, mais um teste de enlaces por circuito ativo. O filtro vale para todas as políticas.
* **Regeneradores:** na passada com regeneradores o formato é único em todo o caminho e os regeneradores são posicionados pelo SNR dos segmentos (AAR sem critério de alcance). Uma solução transparente com formato menos eficiente continua preferida a uma regenerada.
* **Causa de bloqueio:** vale a última falha específica. Como os formatos são tentados do mais eficiente ao mais robusto, é a causa do formato mais robusto (no último caminho): `QOT_NEW`, `CROSSTALK`, `QOT_OTHERS`, `XT_OTHERS` ou `FRAGMENTATION`.
* **Sem QoT:** com `activeQoT = false` não há critério físico e a política usa o alcance, como `distance-adaptive`.

---

## 4. Exemplos de Heurísticas Clássicas
O SNetS2 virá com uma biblioteca de algoritmos base prontos para uso:

*   **Routing:** Dijkstra (Shortest Path), k-Shortest Paths (KSP).
*   **Spectrum Assignment:** First Fit (FF), Random Fit (RF), Last Fit (LF), Exact Fit (EF).
*   **Core Assignment:** First Fit Core, Random Fit Core, Min-Crosstalk Core Assignment.
*   **Modulation:** Fixed Modulation, Distance-Adaptive Modulation, QoT-Adaptive Modulation.

---

## 5. Implementação de Novos Algoritmos
Para adicionar um novo algoritmo ao SNetS2:
1.  Criar uma nova classe que implemente uma das interfaces RMSCA.
2.  Registrar a classe no sistema de mapeamento do simulador.
3.  Referenciar o nome da classe no campo correspondente do JSON de entrada.

Esta estrutura permite que o simulador evolua junto com o estado da arte das redes ópticas elásticas multicore.
