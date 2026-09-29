# SNetS2 — Code Review Completo (implementação × modelo previsto)

> Escopo: leitura integral de `src/main/java` (≈ 6.100 linhas, exceto a GUI) e `src/test/java`, confrontada com `docs/formal_description/` (modelo previsto) e `docs/implementation/` (implementação descrita).
> Método: revisão estática + **execução de micro-experimentos** (harness de Erlang-B, contagem de eventos e sondas numéricas da camada física) para confirmar os achados marcados com ✅ *confirmado empiricamente*.
> Commit revisado: `e64a13d`. Companheiros: [`01_revisao_documentacao.md`](01_revisao_documentacao.md) e [`03_plano_de_verificacao.md`](03_plano_de_verificacao.md).

## Status das correções

| ID | Status | Onde |
| :-- | :-- | :-- |
| CR-01 | ✅ Corrigido: NLI GN em forma fechada (SCI de Poggiolini + XCI por interferente, por vão), sem a divisão extra | `PhysicalLayerModel`; testes `PhysicalLayerMagnitudeTest` (L9-b/c/d) |
| CR-02 | ✅ Corrigido: observação antes da mutação e fechamento da janela no fim | `SetupEvent`, `TeardownEvent`, `SimulationEngine.run`; `ErlangBSingleLinkTest.utilizationMatchesTheory` reativado |
| CR-03 | ✅ Corrigido: o RMSCA usa `IModulationSelection.candidateFormats` (padrão `distance-adaptive`); `SlotCalculator` centraliza o nº de slots | `StandardIntegratedRMSCA`; teste L8-b |
| CR-04 | ✅ Corrigido: flag de medição decidida na chegada e drenagem dos eventos da última requisição | `ArrivalEvent`, `SetupEvent`, `BlockEvent`, `SimulationEngine.run`; testes L1-c |
| CR-08 | ✅ Corrigido: `RandomizedAlgorithm` + sementes derivadas da replicação | `AlgorithmFactory.seedRandomizedAlgorithms`; teste L1-f |
| CR-05 | ✅ Corrigido: XT como razão adimensional (`predictXtRatio`, `predictXT` em dB) e limiar de XT da modulação aplicado ao novo circuito (`CROSSTALK`) e aos ativos (`XT_OTHERS`) | `PhysicalLayerModel`, `StandardIntegratedRMSCA.evaluate`; `RmscaPhysicalConstraintsTest` (L9-e/f/j) |
| CR-06 | ✅ Corrigido: validação única (`evaluate`) para todos os candidatos, inclusive os regenerados; ruído temporário removido em `finally`; ativos iterados sem cópia | `StandardIntegratedRMSCA`; `RmscaPhysicalConstraintsTest` |
| CR-07 | ✅ Corrigido: duas passadas (transparente → com regeneradores) | `StandardIntegratedRMSCA`; `RmscaPhysicalConstraintsTest` |
| CR-09 | ✅ Corrigido: `ConfigValidator` com erros (*fail-fast*) e avisos para chaves ignoradas | `ExperimentalPlanner`; `ConfigValidatorTest` |
| CR-10 | ✅ Corrigido: nº de slots com `polarizationModes` e `rateOfFEC`; PSD calculada na largura de sinal (sem banda de guarda) | `SlotCalculator`, `PhysicalLayerModel.signalBandwidth`; `SlotCalculatorTest` (L0-6) |
| CR-11 | 🔴 Confirmado quantitativamente, ainda não corrigido: −16,7 % (*warm-up* 20 k) e −50 % (60 k) na potência média; também o termo de *add/drop* do OXC é cobrado por transceptor instalado | [Relatório de V&V](04_relatorio_verificacao_validacao.md), E6 |
| CR-12 | 🟡 Parcial: `try/finally` e iteração sem cópia; a verificação continua O(N_ativos) por candidato | — |
| Demais | ⏳ Pendentes (CR-13 a CR-21) | — |

## 0. Resumo executivo

| Severidade | Qtde | Significado |
| :-- | :-: | :-- |
| 🔴 Crítico | 3 | Invalida resultados científicos (números publicáveis estão errados ou a configuração não faz o que diz) |
| 🟠 Alto | 9 | Desvia do modelo documentado / da literatura de forma que altera conclusões, ou quebra reprodutibilidade |
| 🟡 Médio | 6 | Robustez, desempenho, semântica de métricas |
| 🔵 Baixo | 3 | Manutenção e higiene |

**O que está correto (evidência empírica):** o núcleo DES está sólido — a probabilidade de bloqueio de um enlace único (M/M/c/c) coincide com a fórmula de Erlang-B dentro de 1–2 erros-padrão (c=20, A=15 Erlang: simulado 0,04534 ± 0,00017 vs. teórico 0,04559), incluindo com e sem *warm-up*. Geração exponencial, sorteio de pares/taxas, alocação/liberação estrita de espectro, contabilidade de Tx/Rx/regeneradores e a integração temporal da energia estão corretos. Ver §3.

**O que precisa ser corrigido antes de publicar resultados:**

1. **CR-01** — O ruído NLI está ~10¹⁰× menor do que deveria (erro de unidade): a flag `activeNLI` é, na prática, um *no-op*.
2. **CR-02** — Todas as métricas ponderadas no tempo (utilização de espectro, fragmentação, Tx/Rx/regeneradores, conexões ativas) são amostradas **depois** da mutação de estado → viés sistemático (ex.: utilização 0,426 medida vs. 0,333 teórica).
3. **CR-03** — `simulation.modulationSelection` é ignorado: o `experiment01` (“fixed”) roda, de fato, modulação adaptativa de maior ordem possível.

## 1. Achados

Cada achado traz: onde, o que acontece, evidência, impacto e correção proposta. Os IDs são referenciados no plano de verificação.

---

### 🔴 CR-01 — NLI com erro de unidade e modelo que não é o GN documentado
* **Onde:** `metrics/PhysicalLayerModel.java:99` (`generateNliMask`), consumido por `ControlPlane.establishCircuit/teardownCircuit` e `StandardIntegratedRMSCA.applyTemporaryNoise`.
* **O que acontece:** `mi = G³·3γ²/(2π α β₂)` já é uma **densidade espectral** (W/Hz — análise dimensional: γ²G³/(αβ₂) = W/Hz), e a linha 99 divide **de novo** por `bandwidth` (≈5·10¹⁰ Hz).
* ✅ *Evidência:* enlace de 100 km, canal de 4 slots, 0 dBm: NLI no próprio slot = **1,25·10⁻²⁷ W/Hz** vs. ASE do enlace = 4,73·10⁻¹⁷ W/Hz → **NLI/ASE = 2,6·10⁻¹¹**. Uma estimativa GN de forma fechada (Poggiolini) para SCI nas mesmas condições dá ordem de 10⁻¹⁸–10⁻¹⁷ W/Hz (≈ 5–10 % do ASE de 1 vão) — ou seja, ~10⁹–10¹⁰ vezes maior. Variação de potência: NLI ∝ P³ (correto em forma), mas irrelevante em magnitude.
* **Impacto:** com `activeNLI: true` o QoT é praticamente só-ASE (+XT). Não existe potência ótima de lançamento; alcance por QoT superestimado (com só-ASE, 4QAM alcançaria ≈12 800 km vs. `maxRange` 5 000 km configurado — o QoT nunca é o fator limitante); a checagem de QoT-para-outros (`QOT_OTHERS`) por NLI nunca dispara. Os testes atuais só verificam monotonicidade, não magnitude, por isso não detectaram.
* **Outros desvios do modelo documentado (mesmo achado):**
  * A forma `μ·ln(1 + ρ/(Δf/B)²)` não é a do GN/Johannisson/Habibi (o XCI depende de `ln[(|Δf|+B_j/2)/(|Δf|−B_j/2)]` ou `asinh`, com a **largura de banda e a PSD do interferente**, não do vítima).
  * O SCI é emulado por `Δf = B/10` (heurística arbitrária).
  * O SCI do **novo** circuito não entra na predição feita pelo RMSCA (só o cache dos demais), mas passa a entrar na predição dos circuitos ativos depois de estabelecido → o mesmo circuito é avaliado com QoT diferente em momentos diferentes.
  * A máscara é somada em **todos** os slots do núcleo (O(S) por link por setup/teardown), incluindo slots ocupados por outros canais, como pretendido; mas sem normalização por vãos (NLI incoerente entre vãos: `N_span^ε`).
* **Correção:** remover a divisão extra; adotar forma fechada GN (SCI + XCI por interferente com sua `B_j` e `G_j`), documentar a fórmula em `formal_description/07`, e adicionar testes de **magnitude** (ver plano L9: NLI/ASE numa referência de 1 vão; potência ótima `P_opt = (P_ASE/2η)^{1/3}`).

---

### 🔴 CR-02 — Métricas ponderadas no tempo são amostradas *depois* da mudança de estado
* **Onde:** `SetupEvent.java:90`, `TeardownEvent.java:43` (agendam `ResourceUtilizationObservationEvent(time)` **após** `establishCircuit/teardownCircuit`); `ResourceUtilizationMetrics.recordObservation` e as classes irmãs (`External/RelativeFragmentation`, `TransmittersReceiversRegeneratorsUtilization`, `SimulationMetadata`).
* **O que acontece:** cada `recordObservation(t)` pondera o estado **atual** (já pós-evento) pelo intervalo `Δt = t − t_anterior`, durante o qual o estado vigente era o **anterior**. O correto para uma função em degraus é `Σ S(t_k⁺)·(t_{k+1}−t_k)` — i.e., amostrar o estado *antes* da mutação (ou guardar o estado do snapshot anterior).
* ✅ *Evidência (M/M/c/c, 2 nós, 1 slot/req.):*

  | c | A/direção | Utilização teórica `A(1−B)/c` | Simulada |
  | :-: | :-: | :-: | :-: |
  | 1 | 0,5 | 0,3333 | **0,426** |
  | 2 | 1,0 | 0,4000 | **0,423** |
  | 20 | 15 | 0,7158 | 0,7151 (viés pequeno quando o estado varia pouco por evento) |

  O BP na mesma execução está correto (0,3334 e 0,1998 vs. 0,3333 e 0,2000).
* **Impacto:** utilização de espectro, fragmentação vertical/relativa (média temporal), ocupação de Tx/Rx/regeneradores e “conexões ativas médias” têm viés dependente da carga e da granularidade do estado — pior em redes pequenas/poucas conexões. Contradiz a afirmação do doc. `05_metrics_system.md` (“insensível ao acaso… ocupação exata”).
* **Correção:** disparar a observação **antes** de mutar o estado (no início de `SetupEvent`/`TeardownEvent`) ou fazer as classes guardarem `lastSnapshot` e pesá-lo no próximo evento; fechar a janela no fim da simulação (`T_end`). Reabilitar o teste `@Disabled ErlangBSingleLinkTest.utilizationMatchesTheory`.

---

### 🔴 CR-03 — `simulation.modulationSelection` é ignorado; `Fixed`/`DistanceAdaptive` são código morto
* **Onde:** `ExperimentalPlanner.java` (não injeta `IModulationSelection`), `StandardIntegratedRMSCA.java:72` (itera todas as modulações por `M` decrescente); `FixedModulationSelection` e `DistanceAdaptiveModulationSelection` só são usadas em testes.
* **Impacto:** `experiments/experiment01/setup.json` declara `"modulationSelection": "fixed"` mas executa, na prática, “maior M cujo `maxRange` alcança o caminho, com queda para menores em caso de bloqueio de espectro/QoT”. O `results.xlsx` versionado reflete isso. Qualquer estudo “fixa × adaptativa” é inválido. O doc `04_rmsca_logic.md` afirma “3. Modulation: Invoca `IModulationSelection`”.
* **Correção:** o RMSCA deve receber a estratégia e dela obter a **lista ordenada de candidatos** (`fixed` → 1; `distance-adaptive` → ordem por eficiência com filtro de alcance); remover a duplicação da fórmula de nº de slots (hoje copiada em 4 lugares: `StandardIntegratedRMSCA:80`, `Fixed:34`, `DistanceAdaptive:31`, `RelativeFragmentationMetrics:29`).

---

### 🟠 CR-04 — Término e *warm-up* com off-by-one
* **Onde:** `SimulationEngine.run()` (linha 105: `arrivalCounter < maxArrivals`), `isWarmUp()` (linha 154).
* **O que acontece:** (i) ao processar a chegada nº N o contador atinge `maxArrivals` e o laço sai **antes** de executar o `SetupEvent/BlockEvent` dessa requisição → sua chegada é contada e o resultado não; (ii) `recordArrival` usa o contador *antes* do incremento, mas `BlockEvent/SetupEvent` o usam *depois* → o bloqueio da última requisição do *warm-up* é contabilizado sem a sua chegada.
* ✅ *Evidência:* tudo bloqueado, N=1000, warm-up 0 → 1000 chegadas registradas, **999** bloqueios. Com warm-up 100 → 900 chegadas / 900 bloqueios (erros se compensam por acaso).
* **Impacto:** erro O(1/N) no BP, mas quebra o invariante `chegadas = aceitas + bloqueadas` que os testes de verificação precisam; em execuções curtas (N≈10³) é visível.
* **Correção:** marcar a requisição como “medida/aquecimento” **no instante da chegada** (propagar a flag em `AllocationResult`/eventos) e encerrar somente após drenar os eventos da última requisição.

---

### 🟠 CR-05 — Crosstalk: limiar de XT nunca aplicado e métrica de XT em unidade errada
* **Onde:** `ModulationFormat.getCrosstalkThresholdLinear()` (sem chamadas); `PhysicalLayerModel.predictXT` (linha 131 em diante).
* **O que acontece:** (a) o campo `XT` de cada modulação (−19,03 … −31,36 dB) **nunca** é comparado a nada; o XT só entra como ruído no SNR. (b) `predictXT` devolve `10·log10(densidade de ruído em W/Hz)`, não a razão adimensional XT.
* ✅ *Evidência:* 1 vizinho totalmente sobreposto em 100 km → simulador reporta **−168,9 dB**; a razão XT verdadeira (`P_XT/P_sinal = h·L`) é **−31,9 dB** (h = 6,4·10⁻⁹ m⁻¹). A métrica exportada “Average XT (dB)” não é comparável aos limiares nem à literatura.
* **Impacto:** o parâmetro `XT` do JSON é configuração morta; a causa `CROSSTALK` só aparece quando o SNR falha; estatísticas de XT no Excel são inutilizáveis.
* **Correção:** `XT_dB = 10·log10( ⟨I_XT⟩ / I_ch )`; aplicar `XT ≤ limiar(mod)` quando `activeXT`.

---

### 🟠 CR-06 — Caminho “regeneradores restauram o QoT” pula a checagem de QoT-para-outros
* **Onde:** `StandardIntegratedRMSCA.java:113` (bloco `Passed with regenerators!`).
* **O que acontece:** ao recuperar o SNR com regeneradores, a função retorna sucesso imediatamente, sem executar a etapa e2 (`activeQoTForOther`/`activeXTForOther`) que o caminho normal executa. O doc `fluxo_rmsca.md` (passo iv) descreve a checagem como obrigatória.
* **Impacto:** circuitos aceitos podem degradar circuitos ativos abaixo do limiar → `QOT_OTHERS` subestimado.
* **Correção:** refatorar em `evaluateCandidate(...)` único, com regeneradores como parte do candidato, seguido sempre da validação de terceiros.

---

### 🟠 CR-07 — Política de regeneração usa regenerador mesmo quando há modulação transparente
* **Onde:** `StandardIntegratedRMSCA.java:72–99` + `AsSoonAsRequiredRegeneratorAssignment`.
* **O que acontece:** o laço testa a modulação de maior `M` primeiro; se o caminho excede o alcance, e há `regeneratorAssignment`, o algoritmo aloca regeneradores e **aceita** — em vez de tentar antes a modulação de menor ordem que atinge o destino sem regeneração. Ex.: caminho de 500 km usa 64QAM (alcance 312 km) + 1 regenerador, embora 16QAM (1250 km) o atendesse transparentemente.
* **Impacto:** consumo inflado de regeneradores/energia/BP por falta de regeneradores; diverge da prática (regenerar só quando nenhuma modulação alcança). Também não modela custo espectral/latência dos regeneradores (doc `02_network_model` §2.3) — os regeneradores reaproveitam o mesmo slot/core.
* **Correção:** primeiro passe sem regeneradores (todas as modulações), segundo passe com regeneração (ou minimizar nº de regeneradores); documentar a premissa de mesma faixa espectral.

---

### 🟠 CR-08 — `RandomFit*` usam `new Random()` sem semente → simulações não reprodutíveis
* **Onde:** `RandomFitSpectrumAssignment.java:15`, `RandomFitCoreAssignment.java:16`.
* **Impacto:** viola a promessa de reprodutibilidade (`02_simulation_engine.md` §3.1, `01_overview` “reprodutibilidade”); dois runs idênticos com `randomfit` divergem; impossibilita testes de regressão “golden” e a comparação 1 thread × N threads.
* **Correção:** injetar o `RandomGenerator` da engine (semente por replicação) via `ControlPlane`.

---

### 🟠 CR-09 — Chaves de configuração aceitas e silenciosamente ignoradas; padrões perigosos
* ✅ *Verificado por busca no código* — chaves sem nenhum consumidor: `physicalLayerModel`, `crosstalkModel`, `typeOfTestQoT`, `rateOfFEC`, `powerSaturationOfOpticalAmplifier`, `noiseFactorModelParameterA1/A2`, `typeOfAmplifierGain`, `switchInsertionLoss`, `fixedPowerSpectralDensity`, `referenceBandwidthForPowerSpectralDensity`, `polarizationModes`, `kRouting`, `reallocation`, `powerAssignment`, `grooming`, `networkType`, `traffic.loadByPair`, `traffic.loadDistributionPerPair`; `simulation.threads` só é lida pelo `MainRunner` quando não há argumento de CLI.
* **Padrões perigosos:** `traffic.load` ausente → **1,0 Erlang** silencioso (`ExperimentalPlanner.java:210`); `activeMetrics` omitido/parcial → métricas **ativas por padrão** (contrário ao “opt-in” do doc `05_experiment_setup`) — as métricas de fragmentação, ligadas por omissão, tornam a simulação ~6× mais lenta (teste Erlang-B: 43 s → 6,7 s ao desligá-las); `loadByPair` é lido e ignorado.
* **Impacto:** o exemplo de JSON do doc usa IDs de algoritmos do SNetS1 (`newksp`(ok), `modulationbyqotv2`, `csbasdm`, `fsalfav1`, `apamem`, `bestFit`) que **não existem** no registro → `RuntimeException` (só os IDs de `AlgorithmFactory` funcionam).
* **Correção:** `ConfigValidator` que falhe rápido (chave não suportada com valor não-default, `load` XOR `loadByPair`, grafo/adjacências inválidas) e defaults documentados.

---

### 🟠 CR-10 — Nº de slots ignora polarização e FEC; banda de guarda entra na banda de sinal
* **Onde:** `StandardIntegratedRMSCA.java:80` (e cópias). `n = ⌈R / (log₂M · f_slot)⌉ + guardBand`.
* **Desvio da literatura:** enlaces coerentes usam dupla polarização e overhead de FEC: `n = ⌈R·(1+FEC) / (N_pol·log₂M·f_slot)⌉ + G`. Os campos `polarizationModes` (=2) e `rateOfFEC` (=0,25) existem na configuração mas não são lidos. Ex.: 100 G/4QAM → 4 slots (50 GHz) no simulador vs. 3 slots (37,5 GHz) pela fórmula com PDM+FEC; 400 G/64QAM → 6 vs. 4.
* Além disso, `predictSNR`/NLI/XT usam `(fim − início + 1)·f_slot` (inclui slots de guarda) como largura do sinal → SNR ligeiramente pessimista e PSD do sinal subestimada.
* **Impacto:** valores absolutos de BP e de utilização são sistematicamente diferentes dos de referência. Não é “erro” se for premissa declarada — mas hoje **não está documentada em lugar nenhum**.
* **Correção:** centralizar em `SlotCalculator`, tornar `N_pol`/FEC parâmetros efetivos, e documentar a fórmula.

---

### 🟠 CR-11 — Energia: média dividida pelo tempo total (inclui *warm-up*) e premissas de amplificador incoerentes
* **Onde:** `ConsumedEnergyMetrics.java:54` (`totalEnergyJoule / finalTime`) — a energia só integra pós-*warm-up*, o denominador não.
* **Impacto:** potência média subestimada por fator `(T−T_w)/T` quando `warmUpRequests > 0`.
* **Também:** amplificadores estáticos = `⌊L/span⌋ × 100 W` (`TopologyMapper`), enquanto o ASE usa `2 + ⌊L/span⌋` amplificadores; constantes (100 W EDFA, 85 W/grau OXC, 100 W/porta, 150 W, 80 W regenerador, 1,683·TR + 91,333) estão fixas no código e sem documentação/citação; `ModulationFormat.energyPerBit` (0,1) e `symbolRate` (32) são valores fixos não usados.

---

### 🟠 CR-12 — QoT-para-outros: O(N_ativos·S) por candidato, mutação temporária de estado compartilhado
* **Onde:** `StandardIntegratedRMSCA.java:144–150` (`applyTemporaryNoise` → laço sobre `cp.getActiveCircuits()` → `removeTemporaryNoise`).
* **Problemas:** (i) `getActiveCircuits()` **copia** o mapa a cada candidato (caminho×modulação×core); (ii) cada verificação recalcula SNR de **todos** os circuitos ativos, contrariando a alegação de custo O(S) independente do nº de conexões (`06_physical_layer_architecture.md` §4–5); (iii) o RMSCA muta os caches do `Core` durante uma *consulta*, violando a “regra de ouro” (“Módulo de Sistema nunca altera seu próprio estado”) e não usa `try/finally` (uma exceção deixa ruído fantasma); (iv) `Core.removeNliNoise/removeXtNoise` truncam em 0, escondendo erros de contabilidade e deriva de ponto flutuante.
* **Correção:** indexar circuitos por (link, core); avaliar só circuitos cujo espectro/vizinhança é afetado; calcular o delta sem mutar (ou `try/finally`); trocar o *clamp* por asserção em modo estrito.

---

### 🟡 CR-13 — Ordem de eventos simultâneos indefinida
* **Onde:** `Event.compareTo` compara só `time`. `PriorityQueue` não é estável.
* **Impacto:** o desenho depende de eventos com o mesmo `t` (`Setup(t)` → `Observation(t)`; `Arrival(0)`/`Observation(0)` iniciais). Hoje funciona por acaso da ordem de inserção no heap; qualquer refatoração pode reordenar e alterar resultados.
* **Correção:** ordem total `(time, prioridade por tipo, sequência)` e documentá-la.

### 🟡 CR-14 — Deriva numérica dos caches de ruído (a confirmar)
* Somas/subtrações repetidas em `double` acumulam erro; após esvaziar a rede o cache pode ficar ≠ 0 (ou ser truncado a 0). **Ação:** teste de consistência (L9-c do plano): após sequência aleatória de estabelecer/desfazer, cache = recomputo do zero (tolerância relativa 10⁻⁹) e = 0 na rede vazia.

### 🟡 CR-15 — Roteamento recomputado a cada chegada
* `DijkstraRouting`/`KShortestPathsRouting` percorrem **todos** os links para cada nó extraído (O(V·E)) e são recalculados para cada requisição, embora a topologia seja estática (pares origem–destino → caminhos são constantes). `k=3` fixo (`kRouting` ignorado). **Ação:** pré-computar/cachear por par; lista de adjacência.

### 🟡 CR-16 — Semântica das métricas vs. documentação
* Só existe BP **por taxa de bits** (`General Bit Rate BP`); não há BP por **requisição** (o doc `06` promete “General blocking probability”, `BitRateBlockingProbability` como aba separada). `NO_PATH` e `OTHER` caem em “BP by Other”. `blockingCoreId` = último core testado (arbitrário). Utilização por slot é dividida só pelo tempo (pode >1; **não é exportada**), por link×core e por slot também não. Utilização de Tx/Rx/Regen é **contagem média**, não porcentagem (doc `06` §3.5 diz “porcentagem”). “OSNR” é SNR na banda do sinal (não OSNR em 0,1 nm). Entropia de fragmentação normaliza por `ln(S)` sobre a distribuição dos blocos livres (definição própria; ver doc).

### 🟡 CR-17 — Checkpoint/exportação frágeis
* `progress.jsonl` não guarda *fingerprint* do `setup.json` → alterar `requests`/algoritmo e retomar mistura resultados. Se uma replicação falhar, **nada** é exportado (só o checkpoint). Replicações ausentes são gravadas como `0.0` no Excel (indistinguível de zero real). `SimulationConstants` são estáticos globais compartilhados por threads/testes.

### 🟡 CR-18 — Validação de topologia e valores fixos no mapeador
* `TopologyMapper` fixa amplificador (16 dB, NF 5, 100 W), `symbolRate` 32, `energyPerBit` 0,1; `totalCores` do relatório vem do primeiro link; todos os links têm os mesmos núcleos/adjacências; enlaces são **unidirecionais** (é preciso declarar as duas direções); adjacência assimétrica não é detectada (XT só é injetado no vizinho listado); com 1 nó, `ArrivalEvent` entra em laço infinito (`do…while (src == dest)`).

### 🔵 CR-19 — Campos de QoT do `Circuit` nunca preenchidos; cálculos incondicionais
* `Circuit.setAseNoise/setNliNoise/setCrosstalk` nunca são chamados (o “QoT repository” do doc `01_network_model` §2.2 não existe); `SetupEvent` calcula SNR/XT/overlaps **sempre**, mesmo com `CrosstalkStatistics` desativada.

### 🔵 CR-20 — Inicialização duplicada e primeira chegada em t=0
* O “bootstrap” (primeira `ArrivalEvent`, `ObservationEvent(0)`) é duplicado no `ExperimentalPlanner` e em testes; a primeira chegada ocorre em t=0 em vez de `Exp(λ)`. **Ação:** `engine.start()`.

### 🔵 CR-21 — Testes e build
* A suíte pré-existente (28 testes) é de fumaça/monotonicidade; não há asserção de magnitude física nem validação estatística (por isso CR-01/02/04 passaram). `pom.xml` exige `release 25`; em JDK 21 é preciso sobrescrever localmente.

## 2. Matriz doc × código (funcionalidades prometidas)

| Funcionalidade documentada | Estado |
| :-- | :-- |
| DES com FEL, eventos Arrival/Setup/Departure/Teardown/Block | ✅ implementado |
| Observadores **periódicos** Δt_obs (`formal/03` §3.6) | ❌ implementado **orientado a evento**, e com viés (CR-02) |
| `IModulationSelection` no fluxo RMSCA | ❌ código morto (CR-03) |
| PSD fixa (`fixedPowerSpectralDensity`) | ❌ ignorado |
| NLI GN (Johannisson/Habibi) | ❌ heurístico + erro de unidade (CR-01) |
| Limiar de XT por modulação | ❌ nunca aplicado (CR-05) |
| Amplificadores com ganho/NF individuais | ❌ `Amplifier` não é usado no ASE |
| `loadByPair`, `loadDistributionPerPair` | ❌ ignorados |
| Média, desvio e IC ao final (`formal/05` §6) | ❌ só colunas `rep0…repN` (coerente com `formal/06`) |
| Saída CSV alternativa | ❌ só `.xlsx` |
| Checkpoint por replicação | ✅ (com ressalvas, CR-17) |
| Multithreading por replicação | ✅ |
| Reprodutibilidade por semente | ⚠️ falha com `randomfit*` (CR-08) |

## 3. Pontos verificados como corretos
* **Erlang-B** (M/M/c/c, c = 1, 2, 5, 20): BP simulado dentro de ±4 EP da teoria — teste automatizado `verification/ErlangBSingleLinkTest`.
* `RandomGenerator.nextExponential` (transformação inversa com `1−U`), sorteio ponderado de taxas e pares.
* `Spectrum` (alocação/liberação com validação estrita), `Node` (Tx/Rx/regeneradores), `ControlPlane` (atomicidade estabelecer/desfazer).
* Fragmentação externa `1 − maior_bloco/livre_total` e relativa `livres em blocos < c / livres` (formas padrão) e horizontal (OR entre links).
* Energia: integração `∫P dt` atualiza antes de alterar a potência dinâmica (ordem correta).
* SNR por segmento com regeneração (mínimo entre segmentos; XT = máximo) — coerente com regeneração O-E-O.
* `KShortestPathsRouting` segue a estrutura do algoritmo de Yen (validação cruzada contra `networkx` prevista no plano, L7).
* ASE por amplificador `NF·h·ν·(G−1)` (ambas as polarizações) é a forma usual; ordem de grandeza de SNR de 1 vão coerente (≈25 dB para 4 slots @ 0 dBm, 100 km).
* Coeficiente de acoplamento `h = 2κ²R/(βΛ)` (formato de Koshiba/Hayashi) e acúmulo linear de XT entre enlaces.

## 4. Ordem sugerida de correção
1. CR-02, CR-04 (métricas/termo — baratas e desbloqueiam a verificação L2–L3 completa).
2. CR-03, CR-08, CR-09 (configuração honesta e reprodutível).
3. CR-01, CR-05, CR-10 (camada física e nº de slots — mudam números; documentar premissas).
4. CR-06, CR-07, CR-12 (RMSCA e desempenho).
5. Demais (CR-11, 13–21).
