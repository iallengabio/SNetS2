# SNetS2 — Plano de Verificação e Validação (do mais simples ao mais complexo)

> Objetivo: acumular **evidência independente** de que o simulador faz o que o modelo prevê, começando por cenários com solução analítica exata e subindo até redes SDM com camada física, sempre com um **oráculo** (fórmula, referência externa ou invariante) e um **critério de aceitação estatístico** explícito.
> Companheiros: [`01_revisao_documentacao.md`](01_revisao_documentacao.md), [`02_code_review.md`](02_code_review.md) (IDs `CR-xx`).
> Princípio: *verificação* = “construímos o modelo certo?” (código × especificação); *validação* = “o modelo certo é o do mundo real/literatura?” (resultado × referência).

> **Resultados:** a execução dos níveis L2, L3, L4, L6-a, L8-b, L9, L10, L11 e L12-b está no [relatório de verificação e validação](04_relatorio_verificacao_validacao.md).

## 0. Estado inicial e primeira evidência (já executada)

| Experimento | Resultado | Conclusão |
| :-- | :-- | :-- |
| L2-a Erlang-B, c=20, A=15/dir (10 réplicas × 400 k req.) | BP = 0,04534 ± 0,00017 vs. 0,04559 | ✅ passa (≤ 1,5 EP) |
| L2-a c=1, 2, 5 (testes automatizados) | dentro de 4 EP | ✅ passa |
| L2-b Utilização `A(1−B)/c` | c=1: 0,426 vs 0,333; c=2: 0,423 vs 0,400 | ❌ **falha** → CR-02 |
| L1-c Contagem de eventos (tudo bloqueado, N=1000, W=0) | 1000 chegadas, 999 bloqueios | ❌ **falha** → CR-04 |
| L9 NLI/ASE (100 km, 4 slots, 0 dBm) | 2,6·10⁻¹¹ (esperado ≈ 10⁻¹) | ❌ **falha** → CR-01 |
| L9 XT reportado (1 vizinho, 100 km) | −168,9 dB vs. −31,9 dB esperado | ❌ **falha** → CR-05 |

Entregue neste PR: `src/test/java/com/snets2/verification/ErlangBSingleLinkTest.java` (L2-a ativo; L2-b `@Disabled` até CR-02 ser corrigido).

## 1. Metodologia estatística (vale para todos os níveis)

1. **Replicações independentes** com sementes distintas (`repId`); para comparar variantes usar **números aleatórios comuns** (mesma semente) — já é o comportamento atual (mas `randomfit*` precisa de CR-08).
2. **Transiente:** determinar `warmUpRequests` com o método de Welch/MSER-5 num cenário-piloto; depois fixá-lo. Verificar sensibilidade (W ∈ {0, W*, 2W*}).
3. **Erro-padrão:** `EP = s/√n` sobre as réplicas; **critério padrão:** `|média − teoria| ≤ 4·EP + ε_abs`, com `ε_abs` pequeno (10⁻³) para o viés de execução finita; ou **cobertura**: IC 99 % (t de Student) contém a teoria.
4. **Réplicas:** n ≥ 10 (L2–L4), ≥ 20 se BP < 10⁻³; comprimento: ≥ 10⁵ requisições pós-*warm-up* por réplica (BP ≈ 10⁻²) — escalar ∝ 1/BP.
5. **Testes de distribuição:** Kolmogorov–Smirnov (contínuas), χ² (discretas), nível α = 0,001 para evitar falsos alarmes em CI; sementes fixas nos testes de CI para determinismo.
6. **Invariantes** são verificados em **toda** execução (modo estrito), não só em testes dedicados.
7. **Reprodutibilidade:** mesma semente ⇒ *bit-a-bit* mesmos resultados (1 thread e N threads).

## 2. Níveis de verificação

Legenda de prioridade: **P0** bloqueia o restante; **P1** núcleo científico; **P2** completude. “Pré-req.” lista correções de código necessárias para o teste passar.

---

### L0 — Invariantes estruturais e estáticos (segundos, sem estocástica)
| ID | Experimento | Oráculo / critério | Prio | Pré-req. |
| :-- | :-- | :-- | :-: | :-- |
| L0-1 | `Spectrum`: alocar/liberar/dupla alocação/limites | Exceções em modo estrito; `isRangeFree` correto em bordas | P0 | — |
| L0-2 | `Node`: Tx/Rx/regen consumir/liberar/overflow | Contadores nunca < 0 nem > total | P0 | — |
| L0-3 | `ControlPlane`: estabelecer→desfazer restaura **exatamente** espectro, Tx/Rx/regen e caches (rede vazia = zeros) | igualdade exata (espectro) e tol. 10⁻¹² relativa (caches) | P0 | CR-14 |
| L0-4 | Validação de config: chaves ignoradas, `load` ausente, adjacência assimétrica, 1 nó, IDs inexistentes | Falha explícita (não valores silenciosos) | P0 | CR-09, CR-18 |
| L0-5 | Ordem de eventos simultâneos determinística `(t, prioridade, seq)` | Sequência esperada em cenário sintético com empates | P0 | CR-13 |
| L0-6 | Fórmulas puras: nº de slots (tabela R × M × PDM/FEC × guarda), `getBitsPerSymbol`, limiares dB↔linear | Tabela dourada calculada à mão | P0 | CR-10 |

### L1 — Geradores estocásticos e contabilidade de eventos
| ID | Experimento | Oráculo / critério | Prio | Pré-req. |
| :-- | :-- | :-- | :-: | :-- |
| L1-a | Inter-chegadas ~ Exp(λ = load·μ); retenção ~ Exp(μ=1) (10⁶ amostras) | KS (α=0,001); média ± 3 EP; CV = 1 | P0 | — |
| L1-b | Contagem de chegadas em janela T ~ Poisson(λT) | χ² / índice de dispersão = 1 | P1 | — |
| L1-c | Invariante `chegadas = aceitas + bloqueadas` e `Σ causas = bloqueadas`, com e sem *warm-up* | igualdade exata | P0 | **CR-04** |
| L1-d | Mistura de taxas: freq. observada vs. `w_i/Σw` (incl. peso 0, 1 taxa, muitas) | χ² | P1 | — |
| L1-e | Pares (s,d) uniformes sobre N(N−1) pares ordenados; nunca s=d | χ² | P1 | — |
| L1-f | Reprodutibilidade: 2 execuções mesma semente idênticas; 1 thread × 4 threads idênticos; retomada de checkpoint = execução ininterrupta | igualdade bit-a-bit das planilhas | P0 | **CR-08** |
| L1-g | Sensibilidade a `warmUpRequests`: viés de BP em rede que começa vazia | curva BP × W estabiliza em W* | P1 | CR-04 |

### L2 — Enlace único M/M/c/c (Erlang-B) — **oráculo exato**
Topologia: 2 nós, 2 enlaces unidirecionais, 1 core, `c` slots, cada requisição ocupa 1 slot (25 Gbps, 4QAM, guarda 0), QoT desligado, `A_dir = load/2`.

| ID | Experimento | Oráculo | Critério | Prio | Pré-req. |
| :-- | :-- | :-- | :-- | :-: | :-- |
| L2-a | BP × (A, c), c ∈ {1, 2, 5, 20, 100}, A varrendo BP de 10⁻³ a 0,5 | `B(A,c)` por recursão `B_k = A·B_{k−1}/(k + A·B_{k−1})` | critério padrão | P0 | — (**feito**) |
| L2-b | Utilização de espectro | `A(1−B)/c` | critério padrão | P0 | **CR-02** |
| L2-c | Conexões ativas médias (`SimulationMetadata`) | `A(1−B)` (soma dos dois sentidos: `2·A(1−B)`) | critério padrão | P0 | CR-02 |
| L2-d | Duração média das conexões aceitas | 1/μ = 1 | ±3 EP | P1 | — |
| L2-e | Lei de Little: `E[N] = λ_aceita·E[T]` | identidade | 1 % | P1 | CR-02 |
| L2-f | Insensibilidade: retenção não-exponencial (determinística, lognormal) ⇒ mesma BP | Erlang-B (insensível) | critério padrão | P2 | suporte a outras distribuições |
| L2-g | Escala de tempo: multiplicar `load` e `μ` pelo mesmo fator ⇒ resultados invariantes | igualdade estatística | P2 | μ configurável |

### L3 — Recursos de hardware como servidores (Tx/Rx/regeneradores)
| ID | Experimento | Oráculo | Critério | Prio | Pré-req. |
| :-- | :-- | :-- | :-- | :-: | :-- |
| L3-a | Espectro abundante; `tx = c` no nó 0; só o nó 0 origina (ou topologia 2 nós c/ pares simétricos) | BP = `B(A_no, c)`; 100 % das causas = `LACK_OF_TRANSMITTERS` | critério padrão | P0 | — |
| L3-b | Idem para `rx` | `LACK_OF_RECEIVERS` | critério padrão | P0 | — |
| L3-c | Tx e Rx limitantes simultaneamente | Prioridade de causa: Tx testado antes de Rx (documentar) | igualdade | P1 | — |
| L3-d | Ocupação média de Tx/Rx | `A(1−B)` por nó | critério padrão | P1 | CR-02, CR-16 |
| L3-e | Regeneradores: caminho longo obrigatório de 1 regen, `r` regeneradores no nó intermediário | BP = `B(A, r)` (espectro abundante) | critério padrão | P1 | CR-07 |

### L4 — Tráfego heterogêneo em enlace único (Kaufman–Roberts)
Classes `k` com taxa `R_k`, peso `w_k`, demanda `b_k` slots (guarda 0), capacidade `C` slots, `A_k = load_dir·w_k/Σw`.

* **Oráculo:** recursão de Kaufman–Roberts: `j·q(j) = Σ_k A_k·b_k·q(j−b_k)`, `q(0)=1`, normalizar; `B_k = Σ_{j>C−b_k} q(j)/Σ_j q(j)`; BP por taxa `= Σ_k w_k R_k B_k / Σ_k w_k R_k`.
* **Nuance:** o KR **ignora contiguidade**, logo é um **limite inferior** de BP para First-Fit em EON. Verificar: `BP_KR ≤ BP_FF`; e a igualdade estatística quando todas as classes têm `b=1` ou quando `C` é múltiplo dos tamanhos e o alocador é alinhado (First-Fit sobre blocos alinhados).

| ID | Experimento | Critério | Prio | Pré-req. |
| :-- | :-- | :-- | :-: | :-- |
| L4-a | 2 classes {1 slot, 2 slots}, C=10, `DummyFit`/alinhado | BP por classe = KR (critério padrão) | P0 | CR-10 (slots) |
| L4-b | Mesmas classes com First-Fit / Last-Fit / Random-Fit / Exact-Fit | `BP_KR ≤ BP_algoritmo`; ordenar algoritmos e comparar com a literatura (FF tipicamente ≤ RF) | P1 | CR-08 |
| L4-c | 4 classes {100,200,400,800 G} com modulação fixa | KR por classe; BP por taxa correto | P1 | CR-03 |
| L4-d | BP por classe cresce com `b_k` (mais slots ⇒ mais bloqueio) | monotonicidade | P1 | — |

### L5 — Alocação de espectro e fragmentação: testes de cenário determinísticos (“scripted arrivals”)
Sem estocástica: injetar sequências de `AllocationResult`/eventos e verificar posições.

| ID | Experimento | Oráculo | Prio |
| :-- | :-- | :-- | :-: |
| L5-a | FF/LF/EF/RF/Dummy em espectros pré-preenchidos (buracos de tamanhos conhecidos) | intervalo esperado por definição; EF escolhe o menor bloco ≥ n; RF uniforme sobre posições viáveis (χ²) | P0 |
| L5-b | Continuidade em 2–3 enlaces com ocupações complementares | só aloca em slots livres em **todos** os enlaces | P0 |
| L5-c | Fragmentação externa/relativa/entropia em espectros montados à mão | valores fechados (ex.: blocos {10,5}: `F_ext = 1/3`, `F_rel(6) = 1/3`) | P0 |
| L5-d | Fragmentação **horizontal** ao longo do caminho | OR de espectros | P1 |
| L5-e | Média temporal da fragmentação (janela conhecida) | integral exata de trajetória sintética | P1 (CR-02) |
| L5-f | Determinismo do `MinCrosstalkCore` (ordem por ocupação vizinha, desempate por id) | lista esperada | P1 |

### L6 — Redes de perdas multi-enlace (roteamento fixo, 1 slot/req., sem QoT)
| ID | Experimento | Oráculo | Critério | Prio | Pré-req. |
| :-- | :-- | :-- | :-- | :-: | :-- |
| L6-a | **Linha de 3 nós, 2 enlaces em série**, `c` ∈ {2,3,4}, fluxos 0→1, 1→2, 0→2 | **Cadeia de Markov exata** (enumerar `(n_01,n_12,n_02)`; resolver `πQ=0`) ⇒ BP por par | critério padrão | P0 | — |
| L6-b | **Anel de 4–6 nós, caminho mais curto único**, `c` grande | *Erlang fixed-point* (redução de carga): `B_l = E(ρ_l, c)`, `ρ_l = Σ_{r∋l} A_r Π_{m∈r,m≠l}(1−B_m)` | erro relativo ≤ 5 % (a aproximação tem viés) | P1 | — |
| L6-c | Simetria: anel simétrico ⇒ utilização por link igual | ±3 EP entre links | P1 | CR-02 |
| L6-d | **Conservação de carga:** `Σ_links E[ocupação]` = `λ_aceita·E[hops·slots]/μ` | identidade (Little em rede) | 1 % | P0 | CR-02 |
| L6-e | Continuidade de slot: com 2 saltos e `c` pequeno, BP ≥ BP sem continuidade (conversão) — comparar com L6-a | desigualdade | P1 | — |
| L6-f | k-caminhos: BP(KSP k=3) ≤ BP(Dijkstra) em topologia com rotas alternativas; k=1 ≡ Dijkstra | desigualdade / igualdade | P1 | — |

### L7 — Roteamento
| ID | Experimento | Oráculo | Prio |
| :-- | :-- | :-- | :-: |
| L7-a | Dijkstra em 100 grafos aleatórios | distância = `networkx.shortest_path_length` (peso = km) | P0 |
| L7-b | Yen (k=1..5) | lista de custos = `networkx.shortest_simple_paths`; caminhos sem laço, distintos, ordem não-decrescente | P0 |
| L7-c | Grafo desconexo / origem=destino / links unidirecionais assimétricos | `NO_PATH` / erro / rota só na direção existente | P1 |
| L7-d | Empates de comprimento: determinismo do desempate | mesma saída em execuções repetidas | P2 |

### L8 — Modulação, alcance, banda de guarda
| ID | Experimento | Oráculo | Prio | Pré-req. |
| :-- | :-- | :-- | :-: | :-- |
| L8-a | Caminhos de comprimento `maxRange ± ε` para cada formato | escolhe o maior `M` com `L ≤ maxRange`; `L = maxRange` inclui (documentar) | P0 | CR-03 |
| L8-b | `fixed` × `distance-adaptive` no mesmo cenário | `fixed` nunca usa outro formato; `adaptive` usa mais formatos; BP(adaptive) ≤ BP(fixed-mais-robusto) | P0 | **CR-03** |
| L8-c | Tabela nº de slots (R × M × guarda × PDM/FEC) | valores dourados | P0 | CR-10 |
| L8-d | Guarda = 0,1,2: BP crescente; `SpectrumSize` = n+G | monotonicidade + igualdade | P1 | — |
| L8-e | Sem modulação alcançando o destino e sem regeneradores | causa `NO_PATH`/definida (documentar) | P1 | CR-16 |

### L9 — Camada física (QoT) — de componentes isolados a integrados
| ID | Experimento | Oráculo | Critério | Prio | Pré-req. |
| :-- | :-- | :-- | :-- | :-: | :-- |
| L9-a | **ASE**: 1, 2, n vãos; `NF`, `L`, `span` variados | `S_ASE = N_amp·NF·h·ν·(G−1)` calculado à mão; `SNR = I_ch/S_ASE` | 10⁻⁹ rel. | P0 | — |
| L9-b | **NLI (magnitude)**: canal único de 50 GHz, 1 vão de 80 km, 0 dBm | GN forma fechada (SCI): `(8/27)γ²G³L_eff²·asinh(π²/2·\|β₂\|L_eff,a·B²)/(π\|β₂\|L_eff,a)` | ±10 % da forma fechada | P0 | **CR-01** |
| L9-c | **NLI vs. potência**: varredura de `P` | `SNR(P)` tem máximo em `P_opt = (P_ASE/2η)^{1/3}` (η = NLI/P³) e inclinação −2:+1 nas assíntotas | localizar P_opt ±0,5 dB | P0 | **CR-01** |
| L9-d | **NLI vs. Δf**: canal vizinho a Δf crescente | XCI decresce monotonicamente com a forma `ln((\|Δf\|+B/2)/(\|Δf\|−B/2))` | ±10 % | P1 | CR-01 |
| L9-e | **XT — 2 cores**: 1 interferente totalmente sobreposto, comprimento L | `XT = h·L` (`h=2κ²R/(βΛ)`); linear em L; escala com fração de sobreposição; soma para n vizinhos | 10⁻⁹ rel.; unidade adimensional (dB) | P0 | **CR-05** |
| L9-f | **XT — limiar**: circuito aceito ⇔ `XT_dB ≤ XT_th(mod)` | fronteira exata em n de vizinhos | igualdade | P0 | CR-05 |
| L9-g | **Cache incremental:** sequência aleatória (10⁴) de estabelecer/desfazer; após cada passo cache = recomputo do zero a partir dos circuitos ativos; rede vazia ⇒ 0 | tol. relativa 10⁻⁹ | P0 | CR-14 |
| L9-h | **Consistência alcance × QoT:** para cada modulação, alcance por SNR (só ASE+NLI) vs. `maxRange` configurado | Relatório: o QoT é (ou não) o limitante; ajustar `maxRange` ou justificá-lo | P1 | CR-01 |
| L9-i | **Regeneração:** SNR = mínimo por segmento; verificar que regenerar restaura SNR = SNR do maior segmento | igualdade | P1 | — |
| L9-j | **QoT-para-outros:** cenário 3-circuitos onde o novo circuito viola um vizinho ativo ⇒ `QOT_OTHERS`/`XT_OTHERS`; incluindo o ramo “regenerador restaura QoT” | causa correta; nenhum circuito ativo fica < limiar | P0 | **CR-06** |
| L9-k | **Invariante global de QoT:** em qualquer instante de qualquer simulação, todo circuito ativo tem SNR ≥ limiar (checagem em modo estrito) | 0 violações | P0 | CR-01, CR-06 |
| L9-l | **Terminologia:** SNR × OSNR (relação `OSNR = SNR·R_s/B_ref`) | teste de conversão | P2 | doc P1 |

### L10 — SDM (multi-core) sem/ com QoT: comportamento qualitativo e limites
| ID | Experimento | Oráculo | Critério | Prio |
| :-- | :-- | :-- | :-- | :-: |
| L10-a | `n` cores **sem** XT (adjacência vazia) com cores independentes | BP(n cores, `c` slots cada) = `B(A, n·c)` **somente** se o alocador puder usar qualquer core (agrupamento perfeito); First-Fit em cores: comparar com `B(A/n, c)` (partição) como limite superior e `B(A, n·c)` como inferior | limites + critério padrão | P0 |
| L10-b | Ganho de multiplexação SDM: BP decrescente com nº de cores | — | monotonicidade | P1 |
| L10-c | XT ativo × inativo (`activeXT`) com adjacência hexagonal (7 cores) | — | BP(XT ativo) ≥ BP(XT inativo) | P1 |
| L10-d | `MinCrosstalkCore` × `FirstFitCore`: XT médio e bloqueio por `CROSSTALK` | — | Min-XT reduz XT médio; sem piora de BP a baixa carga | P1 |
| L10-e | Simetria das adjacências (7-core hexagonal, 19-core): XT no core central > periféricos | — | ordem esperada | P1 |
| L10-f | Adjacência assimétrica é rejeitada pela validação | — | erro | P0 (CR-18) |

### L11 — Métricas complexas (com oráculo por construção)
| ID | Experimento | Oráculo | Prio | Pré-req. |
| :-- | :-- | :-- | :-: | :-- |
| L11-a | **Energia**: rede fixa, `load` variado | `P̄ = P_estática + λ_aceita·E[P_circuito]/μ` (Little), tol. 1 % | P1 | CR-11 |
| L11-b | Energia com *warm-up*: `P̄` independente de W | invariância | P0 | CR-11 |
| L11-c | Modulação: percentuais somam 1; frequência por (mod, taxa) = frequência do sorteio × regra de seleção | igualdade | P1 | — |
| L11-d | `SpectrumSize`: distribuição = distribuição de `n(R, M)+G` ponderada pelos aceitos | igualdade | P1 | — |
| L11-e | BP por causa: soma das causas = BP geral; cada causa dominante no cenário que a provoca (Tx, Rx, fragmentação, QoT, XT) | igualdade + dominância | P0 | CR-16 |
| L11-f | Fragmentação: `F_ext ∈ [0,1]`; rede vazia/cheia ⇒ 0; monotonia da relativa em `c` | propriedades | P0 | — |
| L11-g | Excel: *round-trip* (escrever → ler com POI/pandas) reproduz valores, colunas e ordenação natural; réplica ausente distinguível de zero | igualdade | P1 | CR-17 |

### L12 — Comparação com referências externas (validação) e literatura
| ID | Experimento | Referência | Critério | Prio |
| :-- | :-- | :-- | :-- | :-: |
| L12-a | **NSFNET (14 nós)/ Cost239 / JPN12**, 1 core, sem QoT, tráfego 100–400 G, KSP+FF, modulação adaptativa por alcance | Curvas BP × carga publicadas (Christodoulopoulos et al.; Chatterjee et al.; Klinkowski) **e** um simulador independente com os mesmos dados (SNetS1, Flex-Net-Sim, ou script Python próprio) | mesma ordem de grandeza e mesma ordenação de algoritmos; diferença < 10 % contra o simulador independente **com premissas alinhadas** (nº de slots, guarda, roteamento) | P1 |
| L12-b | Reprodução das comparações clássicas: FF vs. RF vs. LF vs. EF; KSP vs. SP; guarda | Resultados qualitativos da literatura (ex.: FF ≲ RF; KSP < SP) | ordenação | P1 |
| L12-c | SDM MCF-7 com XT (Lobato/Klinkowski): efeito do XT e do `MinCrosstalk` | Tendências publicadas (BP ↑ com XT; núcleos periféricos melhores) | qualitativo + ordem de grandeza | P2 |
| L12-d | Validação cruzada da camada física contra ferramenta GN de referência (ex.: GNPy) para 1 enlace de N vãos | SNR/OSNR por canal | ±0,5 dB | P2 |

### L13 — Desempenho, escalabilidade, robustez operacional
| ID | Experimento | Critério | Prio |
| :-- | :-- | :-- | :-: |
| L13-a | *Benchmark* eventos/s × (nós, cores, slots, circuitos ativos); com/sem `activeQoTForOther`; métricas ligadas × desligadas | Curvas registradas; regressão < 20 % entre versões; validar a alegação “O(S) independente de conexões” | P1 (CR-12, CR-15) |
| L13-b | Escalabilidade de *threads* (1,2,4,8 réplicas paralelas) | speed-up ≥ 0,7·n; resultados idênticos (L1-f) | P1 |
| L13-c | Checkpoint: matar processo no meio, retomar | resultado final = execução ininterrupta; `setup.json` alterado ⇒ recusa (fingerprint) | P1 (CR-17) |
| L13-d | Memória: 10⁷ requisições, sem vazamento (FEL, histórico do `SimulationMetadata`) | heap estável | P2 |
| L13-e | Testes de mutação/propriedades (jqwik) em `Spectrum`, caches e alocadores | 0 contraexemplos em 10⁵ casos | P2 |

## 3. Organização proposta no repositório

```
src/test/java/com/snets2/verification/      # L0–L11 (JUnit 5; rápidos <2 min no CI; sementes fixas)
    ErlangBSingleLinkTest.java              # ✅ já incluído (L2-a; L2-b desativado até CR-02)
    KaufmanRobertsTest.java                 # L4
    TandemMarkovChainTest.java              # L6-a (resolve a cadeia em Java, sem dependências)
    SpectrumScriptedTest.java               # L5
    PhysicalLayerMagnitudeTest.java         # L9
    InvariantsTest.java                     # L0/L1-c/L9-k (modo estrito habilitado)
experiments/verification/                   # L12–L13 (setups JSON longos, fora do CI rápido)
scripts/verification/                       # Python: oráculos (networkx, Markov, KR), plots e relatório
docs/review/                                # este plano + relatório de resultados (tabela PASS/FAIL por ID)
```

* **Perfis Maven:** `mvn test` (L0–L11 rápidos) e `mvn -Pverification verify` (L12–L13, horas).
* **Relatório:** cada execução do perfil longo gera `docs/review/results/<data>.md` com tabela `ID | oráculo | obtido ± EP | veredito`.
* **Regras de CI:** falha de qualquer L0–L11 bloqueia *merge*; L12 gera *warning* com desvio percentual.

## 4. Roteiro (ordem de execução recomendada)

| Fase | Conteúdo | Saída | Depende de |
| :-: | :-- | :-- | :-- |
| **F0** | L0 completo, L1-a/c/d/e, L2-a (**feito**), L7 | Base confiável do DES e do roteamento | CR-04, CR-13 (pequenas) |
| **F1** | Corrigir **CR-02**, CR-08, CR-09; L2-b/c/d/e, L3, L1-f/g, L5, L6-a/d | Núcleo estocástico e métricas temporais validados contra oráculos exatos | — |
| **F2** | **CR-03**, CR-10; L4, L8, L6-b/c/e/f | Tráfego heterogêneo, modulação e slots validados | F1 |
| **F3** | **CR-01, CR-05, CR-06**, CR-14; L9 completo | Camada física com magnitude correta e limiares aplicados | F2 |
| **F4** | CR-07, CR-12, CR-15; L10, L11 | SDM, regeneração e métricas compostas | F3 |
| **F5** | L12 (comparação externa), L13 (desempenho/robustez) | Relatório de validação e *baseline* de desempenho | F4 |

Critério de saída de cada fase: todos os IDs P0 da fase com veredito PASS; P1 PASS ou justificativa registrada; documentos `formal/` e `implementation/` atualizados (regra *Documentation Sync*).

## 5. Referências de oráculos (fórmulas)

* **Erlang-B:** `B(A,c) = (A^c/c!)/Σ_{k=0}^{c} A^k/k!`; recursão estável `B_0=1`, `B_k = A B_{k−1}/(k + A B_{k−1})`.
* **Kaufman–Roberts:** `j·q(j) = Σ_k A_k b_k q(j−b_k)`; `B_k = Σ_{j=C−b_k+1}^{C} q(j) / Σ_{j=0}^{C} q(j)`.
* **Little:** `E[N] = λ_ef·E[T]`; energia média `P̄ = P_est + λ_ef·E[P_circ]·E[T]`.
* **Ponto fixo de Erlang (redução de carga):** `B_l = E(ρ_l,c)`, `ρ_l = Σ_{r∋l} A_r Π_{m∈r∖l}(1−B_m)`, `BP_r = 1 − Π_{l∈r}(1−B_l)`.
* **ASE:** `S_ASE = NF·h·ν·(G−1)` por amplificador (W/Hz, 2 polarizações); `SNR = I_ch/(ΣS_ASE + I_NLI + I_XT)`, `I_ch = P/(n_slots·f_slot)`.
* **GN (SCI, canal único):** `G_NLI = (8/27)γ²G³L_eff²·asinh((π²/2)|β₂|L_eff,a B²)/(π|β₂|L_eff,a)`; ótimo `P_opt = (P_ASE/(2η))^{1/3}`.
* **XT MCF:** `h = 2κ²R/(βΛ)`; `XT ≈ n·h·L` (pequeno); exato `XT = n(1−e^{−(n+1)2hL})/(1+n e^{−(n+1)2hL})`.
* **Yen/Dijkstra:** oráculo = `networkx` (`shortest_path_length`, `shortest_simple_paths`).

> Reprodutibilidade da evidência da §0: o harness usado (constrói o cenário a partir do JSON, roda a engine e imprime BP/utilização) replica o fluxo de `ExperimentalPlanner.runReplicationAndSaveProgress`; o teste `ErlangBSingleLinkTest` é a forma permanente dele. Recomenda-se extrair essa montagem para uma fábrica de teste (`TestScenarios`) — e, idealmente, para `engine.start()` (CR-20) — e reutilizá-la em todos os níveis acima.
