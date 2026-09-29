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
*   **`ICoreAssignment`**: Recebe um caminho e retorna a lista ordenada de núcleos (`Core`) candidatos. Uma sobrecarga recebe também o número de slots da demanda e a política espectral, para estratégias que olham os slots que cada núcleo receberia (§3.2); por padrão ela ignora esses dados.
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

#### Margem de QoT (`qot-margin`)
Porte de `ModulationSelectionByQoTAndSigma` do SNetS v1 (parâmetro σ do algoritmo KSP-RQoTO de Fontinele et al., 2017 [1]). Com as margens $\sigma$ (SNR) e $\sigma_{XT}$ (XT), em dB, a política escolhe, para cada caminho $p$, o primeiro candidato $(m, c, I)$ viável segundo as condições de `qot-adaptive` que também satisfaz

$$SNR_{novo} \ge SNR_{th}(m) + \sigma, \qquad XT_{novo} \le XT_{th}(m) - \sigma_{XT} \quad (\text{em dB}).$$

Se nenhum candidato do caminho mantém as margens, vale o primeiro candidato viável do caminho, que é a escolha de `qot-adaptive`. As margens são uma preferência e nunca bloqueiam. Aplicam-se só ao novo circuito, e os estabelecidos continuam sujeitos apenas aos próprios limiares. Com $\sigma = \sigma_{XT} = 0$ a política é idêntica a `qot-adaptive`, e sem QoT volta ao alcance.

* **Parâmetros** (`simulation.algorithmParameters`): `sigma` e `sigmaXt`, em dB, ambos $\ge 0$ e com padrão 0.
* **Diferenças em relação ao v1.** A margem de XT é uma extensão para SDM e não existe no v1. No v1, a escolha de rota do KSP-RQoTO (menor slot inicial com melhor pior-margem dos estabelecidos) é um algoritmo integrado e não foi portada. Aqui vale a precedência de caminhos do RMSCA padrão.
* **Monotonicidade.** Enquanto algum formato mantém $\sigma$, aumentar $\sigma$ nunca escolhe um formato mais eficiente. Quando nenhum mantém, a escolha volta à de `qot-adaptive`.

[1] A. Fontinele, I. Santos, J. N. Neto, D. R. Campelo, A. Soares, "An efficient IA-RMLSA algorithm for transparent elastic optical networks", *Computer Networks* 118 (2017) 1–14, doi:[10.1016/j.comnet.2017.03.003](https://doi.org/10.1016/j.comnet.2017.03.003).

### 3.2. Atribuição de núcleo e crosstalk

Seja $\mathcal{C}(p)$ o conjunto de núcleos presentes em todos os enlaces de $p$ e $\mathrm{adj}(c)$ os vizinhos de $c$ na fibra (lista `adjacentCores` do primeiro enlace). As estratégias disponíveis são:

* **First-Fit core** (`firstfitcore`): ordem crescente de id. Na MCF hexagonal de 7 núcleos começa pelo núcleo 0, o central, com 6 vizinhos.
* **Random-Fit core** (`randomfitcore`): permutação aleatória (gerador da replicação).
* **Min-crosstalk core** (`mincrosstalkcore`/`mincrosstalk`): ordem crescente de $\sum_{l \in p} \sum_{a \in \mathrm{adj}(c)} |O_l(a)|$, a ocupação total dos vizinhos, **sem olhar os slots candidatos**. Comportamento inalterado.
* **Peripheral-first core** (`peripheralfirstcore`): ordem fixa construída de forma gulosa. A cada passo escolhe o núcleo restante com menos vizinhos entre os já ordenados, depois com menos vizinhos no total, depois com menor id. Na MCF hexagonal de 7 núcleos a ordem é $1, 3, 5, 2, 4, 6, 0$: os três núcleos externos não adjacentes entre si, os outros três externos e o central por último.
* **XT-aware core** (`xtawarecore`): ordem pelo crosstalk no intervalo que o núcleo **realmente receberia**. Para cada $c \in \mathcal{C}(p)$, seja $I_c = [s_c, e_c]$ o intervalo de $n$ slots proposto pela política espectral configurada em $c$ (First-Fit quando a política é aleatória, para não consumir o seu gerador). O custo é

$$\mathrm{custo}(c) = \sum_{l \in p} L_l \sum_{a \in \mathrm{adj}(c)} |O_l(a) \cap I_c|,$$

  com $L_l$ o comprimento do enlace e $O_l(a)$ os slots ocupados no núcleo $a$ do enlace $l$. Com o modelo $XT = \sum h L$ sobre os vizinhos sobrepostos e PSD de lançamento igual por slot, esse custo é proporcional tanto ao XT que o novo circuito sofreria quanto ao XT que ele injetaria nos circuitos ativos dos núcleos adjacentes. Os núcleos são ordenados por custo crescente; empates seguem a ordem peripheral-first, e núcleos sem intervalo livre vão para o fim. A ordem é determinística. A ocupação vem dos bitsets de espectro, que são mantidos mesmo quando os caches de NLI/XT estão desligados. Sem o tamanho da demanda (chamada de `selectCores(cp, path)`), devolve a ordem peripheral-first.

#### Espectro escalonado por núcleo (`corestaggeredfit`)

Política espectral com um ponto de partida por núcleo, para manter núcleos adjacentes em partes disjuntas da banda enquanto possível. Os núcleos são coloridos de forma gulosa (na ordem peripheral-first, cada núcleo recebe a menor cor não usada por um vizinho já colorido), de modo que núcleos adjacentes nunca têm a mesma cor. Com $k$ cores e $N$ slots:

* cor 0: First-Fit a partir do slot 0;
* cor 1: Last-Fit a partir do slot $N-1$;
* cor $q \ge 2$: First-Fit a partir do slot $\lfloor N (q-1)/(k-1) \rfloor$, voltando ao slot 0 no fim da banda.

Na MCF hexagonal de 7 núcleos, os núcleos 1, 3, 5 enchem de baixo para cima, 2, 4, 6 de cima para baixo e o central começa no meio da banda. Sem adjacência (núcleo único) equivale ao First-Fit. A política pode ser combinada com qualquer estratégia de núcleo.

**Resultado no E8** (NSFNET × 0,25, MCF de 7 núcleos, 128 slots, ASE + NLI + XT, 5 réplicas): `xtawarecore` + `corestaggeredfit` tem bloqueio menor ou igual ao do Random-Fit core em todas as cargas (0 × 0,0056 em 400 Erl; 0,0111 × 0,0154 em 600; 0,052 × 0,055 em 800; 0,100 × 0,104 em 1000; 0,151 × 0,161 em 1200; nas duas últimas os intervalos de confiança se sobrepõem). Com First-Fit de espectro, `xtawarecore` e `peripheralfirstcore` zeram o bloqueio em 400 Erl e ficam melhores que First-Fit core e Min-crosstalk core em todas as cargas, mas ligeiramente acima do Random-Fit core entre 600 e 1000 Erl (empate em 1200 Erl): o espectro First-Fit alinha os mesmos slots baixos em todos os núcleos, e o bloqueio passa a ser dominado pelo XT do novo circuito. O escalonamento espectral por núcleo é o que remove essa sobreposição.

---

## 4. Exemplos de Heurísticas Clássicas
O SNetS2 virá com uma biblioteca de algoritmos base prontos para uso:

*   **Routing:** Dijkstra (Shortest Path), k-Shortest Paths (KSP).
*   **Spectrum Assignment:** First Fit (FF), Random Fit (RF), Last Fit (LF), Exact Fit (EF), Core-Staggered Fit.
*   **Core Assignment:** First Fit Core, Random Fit Core, Min-Crosstalk Core, Peripheral-First Core, XT-Aware Core.
*   **Modulation:** Fixed Modulation, Distance-Adaptive Modulation, QoT-Adaptive Modulation, QoT-Adaptive com margem.

---

## 5. Implementação de Novos Algoritmos
Para adicionar um novo algoritmo ao SNetS2:
1.  Criar uma nova classe que implemente uma das interfaces RMSCA.
2.  Registrar a classe no sistema de mapeamento do simulador.
    Se o algoritmo tiver parâmetros numéricos, implementar `Configurable` e lê-los de `simulation.algorithmParameters`.
3.  Referenciar o nome da classe no campo correspondente do JSON de entrada.

Esta estrutura permite que o simulador evolua junto com o estado da arte das redes ópticas elásticas multicore.
