# SNetS2 — Revisão da Documentação (precisão técnica e aderência à literatura)

> Escopo: `README.md`, `GEMINI.md`, `docs/development_status.md`, `docs/fluxo_rmsca.md`, `docs/formal_description/01–07`, `docs/implementation/README + 01–08`.
> Critérios: (A) a descrição é **cientificamente correta** e coerente com a literatura de EON/SDM?; (B) é **fiel ao código**?; (C) está **completa** (GEMINI.md exige derivação matemática de toda métrica)?; (D) é **internamente consistente**?
> Legenda: ❌ incorreto · ⚠️ impreciso/desatualizado · ➕ ausente · ✏️ editorial. Os IDs `CR-xx` remetem a [`02_code_review.md`](02_code_review.md).
> **Este PR não corrige os documentos existentes** (só acrescenta um link no README) — as correções propostas aqui devem ser aplicadas em PR próprio, junto com as correções de código (a regra “Documentation Sync” do `GEMINI.md` vale para ambos).

## 0. Veredito

A documentação é bem organizada e a separação *formal* × *implementação* é um ótimo ponto de partida. Porém, hoje ela **descreve o simulador pretendido, não o simulador existente** em vários pontos-chave (observadores periódicos, seleção de modulação, NLI, XT, PSD fixa, estatísticas finais) e contém **imprecisões físicas** (terminologia OSNR/SNR, formulação do NLI, fórmulas ausentes). Sete pontos exigem atenção imediata:

1. `formal/07` descreve o NLI como GN (Johannisson/Habibi); o código usa uma heurística com erro de unidade (CR-01).
2. `formal/03` §3.6 descreve observadores **periódicos**; `implementation/05` e o código são **orientados a evento** (e enviesados, CR-02). Os dois docs se contradizem.
3. `implementation/04` diz que o RMSCA invoca `IModulationSelection`; não invoca (CR-03).
4. `formal/05` §6 promete média/desvio/IC ao final; `formal/06` §4 (e o código) só exportam `rep0…repN`.
5. O JSON de exemplo (`formal/05`) usa IDs de algoritmo inexistentes → não executa (CR-09).
6. Nenhum documento define o **número de slots** por requisição — a premissa mais influente nos resultados (CR-10).
7. `implementation/05_metrics_system` afirma que a utilização é “exata / insensível ao acaso”; o experimento M/M/c/c mostra o contrário (CR-02).

---

## 1. `README.md` e `GEMINI.md`

| # | Tipo | Onde | Problema | Correção proposta |
| :-: | :-: | :-- | :-- | :-- |
| R1 | ⚠️ | README “Key Features” | “Physical Layer Modeling… OSNR (ASE, non-linear impairments)” apresentado como pronto; NLI hoje é ineficaz (CR-01) e PSD fixa/polarização/FEC não existem | Acrescentar seção *Known limitations* com link para este review |
| R2 | ⚠️ | README “Flexible RMSCA” | Lista “Dijkstra, First-Fit, Random-Fit”; omite KSP, Last/Exact/Dummy Fit, Min-XT, regeneradores AAR | Listar o que existe no `AlgorithmFactory` |
| R3 | ⚠️ | README “Project Structure” | Omite `config/`, `output/`, `gui/`, `util/`; cita `Transceiver` (não existe) | Atualizar árvore |
| R4 | ⚠️ | README “Experimental Planner” | “parallel/sequential replication handling” ok; sem menção a checkpoint (`progress.jsonl`) | Linkar `implementation/07` |
| R5 | ✏️ | GEMINI.md | “Language Decision: TBD” (Java já adotado); regra “Files: snake_case” contradiz arquivos `.java` em PascalCase | Atualizar |
| R6 | ➕ | README | Não informa que o build exige **JDK 25** (`release 25`) nem como rodar os testes de verificação | Adicionar |

## 2. `docs/development_status.md`

| # | Tipo | Problema | Correção |
| :-: | :-: | :-- | :-- |
| S1 | ⚠️ | Fase 3 marcada “Concluída” (“Implementar cálculo de OSNR (ASE e NLI)… modelo de XT”) — NLI ineficaz, XT sem limiar (CR-01, CR-05) | Marcar como “implementada, **não validada**” e apontar o plano `03` |
| S2 | ⚠️ | “paralelismo (via loops sequenciais nesta v1)” e “Checkpointing (preparado estruturalmente)” estão desatualizados: ambos estão implementados (`implementation/07`) | Atualizar |
| S3 | ➕ | Sem status de validação científica | Adicionar Fase 6 “Verificação e Validação” (ver plano) |

## 3. `docs/fluxo_rmsca.md`

| # | Tipo | Problema | Correção |
| :-: | :-: | :-- | :-- |
| F1 | ✏️ | Links `file:///Users/iallen/Dev/...` (caminho local; quebrados para qualquer outro leitor) — também em `implementation/07` | Trocar por caminhos relativos |
| F2 | ⚠️ | Passo “iv. Validação de QoT nos vizinhos” é dito obrigatório, mas o ramo “regeneradores restauram QoT” retorna sem executá-lo (CR-06) | Corrigir código; manter texto |
| F3 | ⚠️ | Causas de bloqueio: `OTHER` (usada como padrão e em falha de regenerador) não é listada; `NO_PATH` também é usada quando **nenhuma modulação alcança** o destino (não só ausência de rota) | Completar enum |
| F4 | ⚠️ | Descreve laço `Modulação × Core` como estratégia fixa; não diz que `IModulationSelection` **não** é usada (CR-03) | Explicitar |
| F5 | ➕ | Não menciona a política “maior modulação primeiro, com regeneração antes de modulação menor” (CR-07) | Documentar ou corrigir |
| F6 | ✅ | Fluxo, ordem dos laços (caminho → modulação → core → espectro → regenerador → QoT) e classificação `CROSSTALK`/`XT_OTHERS` **conferem** com o código | — |

## 4. `docs/formal_description/`

### 4.1 `01_overview.md`
| # | Tipo | Problema | Correção |
| :-: | :-: | :-- | :-- |
| O1 | ✏️ | “Fisura Spectrale” (italiano) e numeração 1,2,3,**6** nas “métricas-alvo” (“quatro pilares” com três itens numerados) | Corrigir: “Fragmentação espectral”; renumerar |
| O2 | ⚠️ | Objetivo “milhares de requisições **por segundo**” / “centenas de nós”: o DES usa tempo abstrato (μ=1) e não há *benchmark*; Dijkstra/KSP são O(V·E) por requisição e o QoT-para-outros é O(N_ativos) (CR-12, CR-15) | Reformular como meta e publicar *benchmark* (plano L12) |
| O3 | ⚠️ | Checkpoint descrito como “estado do `experimentalPlanning` salvo **periodicamente**”; na realidade é por **replicação concluída** (append em `progress.jsonl`); nada é salvo *dentro* de uma replicação | Ajustar |
| O4 | ⚠️ | Entidade `Connection`; no código é `Circuit` | Padronizar nome |
| O5 | ⚠️ | “Modelagem de carga em Erlangs… μ=1”: esclarecer que `load` é a **carga total da rede** (λ = load·μ, pares sorteados uniformemente, tempo médio de retenção = 1 u.t.) e que a carga por par é `load/(N(N−1))` | Acrescentar |

### 4.2 `02_network_model.md`
| # | Tipo | Problema | Correção |
| :-: | :-: | :-- | :-- |
| N1 | ❌ | §2.5: “o simulador considera o ganho e a NF **de cada amplificador**” — o ASE usa NF global e ganho = perda do vão; `Amplifier` (16 dB/NF 5 fixos) só entra na energia (CR-11, CR-18) | Corrigir texto ou implementar |
| N2 | ⚠️ | §2.3: regenerador “consome recursos espectrais extras e adiciona latência” — não modelado (mesmo slot/core; sem latência) | Declarar como premissa/limitação |
| N3 | ⚠️ | §3.1 lista a ordem Routing → **Core** → Modulation → Spectrum; `formal/04` §3 e o código usam Routing → **Modulation** → Core → Spectrum (com laços aninhados) | Unificar |
| N4 | ❌ | §3.2/§3.3: `Circuit` “armazena métricas de QoT (ASE/NLI/XT)… sem novo cálculo”: campos nunca preenchidos; QoT de ativos é recalculado a partir dos caches a cada consulta (CR-12, CR-19) | Corrigir |
| N5 | ⚠️ | §3.4 “Topologia dinâmica (falhas)” — não existe | Marcar como futuro |
| N6 | ✏️ | Duas seções “5.” | Renumerar |
| N7 | ➕ | Não diz que a **continuidade de core** é imposta (mesmo core em todo o caminho) nem que enlaces são **unidirecionais** | Acrescentar |
| N8 | ⚠️ | §2.4 “Spans… geralmente separados por amplificadores” ok, mas falta a regra do simulador: nº de amplificadores de linha = ⌊L/span⌋ (+booster +pré) | Documentar |

### 4.3 `03_simulation_engine.md`
| # | Tipo | Problema | Correção |
| :-: | :-: | :-- | :-- |
| E1 | ❌ | §3.6: observadores **periódicos** com Δt_obs; implementação é orientada a evento (uma observação por Setup/Teardown) e enviesada (CR-02). Contradiz `implementation/05` | Alinhar (recomendado: amostragem pré-mutação) |
| E2 | ⚠️ | “Regra de ouro: o Módulo de Sistema nunca altera seu próprio estado” — violada por `applyTemporaryNoise` (CR-12) | Corrigir código ou qualificar |
| E3 | ⚠️ | §5 “termina quando o contador de Arrival atinge `requests`” — a última requisição não tem Setup/Block processados (CR-04) | Corrigir e documentar |
| E4 | ➕ | Não especifica a **ordem de eventos simultâneos** (CR-13) nem que a 1ª chegada ocorre em t=0 | Acrescentar |
| E5 | ➕ | `warmUpRequests` (descarte) não aparece na especificação do JSON | Documentar em `formal/05` |
| E6 | ✅ | Ciclo Arrival → Setup → Departure → Teardown e diagrama de fluxo conferem | — |

### 4.4 `04_rmsca_algorithms.md`
| # | Tipo | Problema | Correção |
| :-: | :-: | :-- | :-- |
| A1 | ⚠️ | `ICoreAssignment` “retorna o índice do núcleo”; código retorna **lista ordenada** de candidatos | Corrigir |
| A2 | ⚠️ | `IModulationSelection` descrita como parte do fluxo (ver CR-03) | Corrigir código |
| A3 | ➕ | Ausentes: `IRegeneratorAssignment`/AAR, validação de QoT no fluxo, `AllocationResult` no `formal` | Acrescentar |
| A4 | ⚠️ | *Exact Fit*: na literatura é “bloco livre de tamanho exatamente igual, senão *fallback*”; aqui é “menor bloco que comporta” (best-fit). *Random Fit*: sorteia uma **posição inicial** viável uniformemente (blocos maiores têm mais peso), não “um dos blocos” | Precisar definições |
| A5 | ⚠️ | Registro de nomes (`djk`, `newksp`, `mincrosstalkcore`, `lf`, `ef`…) não está listado; `k=3` fixo (`kRouting` ignorado) | Tabela de IDs |
| A6 | ✏️ | “Reflexão” — o registro é um mapa `String→Class` (não há carregamento por nome de classe) | Ajustar |

### 4.5 `05_experiment_setup.md`
| # | Tipo | Problema | Correção |
| :-: | :-: | :-- | :-- |
| X1 | ❌ | Bloco de código da seção `traffic` está quebrado (o ``` fecha antes de `bitRates`) | Corrigir Markdown |
| X2 | ❌ | JSON de exemplo usa `modulationbyqotv2`, `csbasdm`, `fsalfav1`, `apamem`, `bestFit`… inexistentes (CR-09); “kRouting/reallocation/powerAssignment/grooming” ignorados | Exemplo executável + tabela “suportado/ignorado” |
| X3 | ⚠️ | `modulations` está descrita nos bullets de `networkTopology` mas ausente do exemplo; `symbolRate` (32 Gbaud) e `energyPerBit` são fixos no código | Incluir |
| X4 | ⚠️ | “`load` XOR `loadByPair`”: `loadByPair` não é suportado; `load` ausente vira 1,0 (CR-09) | Validar |
| X5 | ❌ | §6 “Estatísticas finais: Média, Desvio Padrão e IC” — **não** são calculados (só `rep0…repN`) | Remover ou implementar |
| X6 | ⚠️ | “semente diferente por replicação”: semente = `repId`; **a mesma** para todos os cenários (CRN) — vantajoso para comparação, mas deve ser dito; `randomfit*` ignoram a semente (CR-08) | Documentar |
| X7 | ➕ | Sem unidades/faixas típicas: `power` (dBm), `fiberLoss` (dB/km), `fiberNonlinearity` (1/(W·m); 0,0013 = 1,3 /W/km), `fiberDispersion` (s/m²; 1,6·10⁻⁵ = 16 ps/(nm·km)), `propagationConstant` (1/m), `bendingRadius` (m), `corePitch` (m), `bvtSpectralWidth` (Hz = **largura do slot**) | Tabela de unidades |
| X8 | ➕ | Não lista `totalSlots`, `warmUpRequests`, `threads` (usados no código) | Acrescentar |
| X9 | ⚠️ | `links` “bidirecionais ou unidirecionais, a definir”: **são unidirecionais**; `cores` é homogêneo em todos os links | Decidir e documentar |
| X10 | ⚠️ | 12 chaves de `physicalLayer` são lidas e ignoradas (lista em CR-09) | Marcar “reservado” |
| X11 | ✅ | Semântica dos pesos de `bitRates` (P = w_i/Σw; 100 G 2× mais provável que 200 G no exemplo) e λ = load·μ **conferem** | — |

### 4.6 `06_output_metrics.md`
| # | Tipo | Problema | Correção |
| :-: | :-: | :-- | :-- |
| M1 | ❌ | Nomes de sub-métricas/abas divergem do código: doc “General blocking probability”, “by QoTN/QoTO”; código “General Bit Rate BP”, “BP by QoT New/Others”; não há aba `BitRateBlockingProbability`; **o exemplo Python filtra um nome que não existe** (retornaria vazio) | Alinhar nomes |
| M2 | ❌ | Prometida BP **por requisição**; só há BP ponderada por taxa (CR-16) | Implementar ou remover |
| M3 | ⚠️ | `SpectrumUtilization` “por Link×Core” e “por Slot” prometidas; só Geral/Link/Core exportadas | Alinhar |
| M4 | ⚠️ | `GroomingStatistics`, `DataSetInformation`, CSV alternativo: inexistentes; `SimulationMetadata` existe mas não é descrita aqui | Alinhar |
| M5 | ⚠️ | Checkpoint “verifica resultados parciais **no arquivo de saída**”: usa `progress.jsonl`, não o `.xlsx` | Corrigir |
| M6 | ➕ | **Nenhuma fórmula** de métrica (violação do GEMINI.md, “Scientific Rigor”). Ver §6 abaixo | Adicionar |
| M7 | ⚠️ | “Average XT (dB)” — no código é dB(W/Hz), não razão adimensional (CR-05) | Corrigir |
| M8 | ➕ | Não diz que réplicas ausentes saem como `0.0` no Excel (CR-17) | Documentar/corrigir |

### 4.7 `07_physical_layer_models.md` (precisão física)
| # | Tipo | Problema | Correção |
| :-: | :-: | :-- | :-- |
| P1 | ⚠️ | Terminologia: a razão `I_ch/(I_ASE+I_NLI+I_XT)` é **SNR** (ou GSNR) na banda do sinal; **OSNR** é referida a 0,1 nm (12,5 GHz): `OSNR = SNR·(R_s/B_ref)` (por polarização/soma de polarizações conforme definição). O texto usa OSNR e SNR como sinônimos | Padronizar: usar “SNR (GSNR)” e definir a relação com OSNR |
| P2 | ❌ | §2 PSD fixa (`fixedPowerSpectralDensity`, `P_laser/B_ref`) descrita como suportada — ignorada pelo código (só “potência por circuito constante”, `I_ch = P/B`) | Implementar ou remover |
| P3 | ⚠️ | §3 ASE: dá só “função de G e NF”. Fórmula usual: `S_ASE = n_sp·h·ν·(G−1)` por polarização; com duas polarizações e `NF ≈ 2n_sp`, `S_ASE ≈ NF·h·ν·(G−1)` (é o que o código faz). Regra de amplificadores do código: `2 + ⌊L/span⌋` (booster + pré + linha), com **ganho de todos = perda de um vão completo** (também no último vão, que é mais curto) e sem perda de inserção do ROADM (`switchInsertionLoss` ignorado) | Escrever a fórmula e as premissas |
| P4 | ❌ | §4 NLI: afirma modelo de Johannisson/Habibi/GN. A implementação **não** é GN (CR-01). Afirma também que o NLI “diminui **drasticamente**” com Δf — no GN o XCI decai lentamente (∼`ln[(|Δf|+B/2)/(|Δf|−B/2)] ≈ B/|Δf|`), dominado pelos vizinhos próximos, mas não “drasticamente” | Reescrever com a forma fechada: `G_NLI = (8/27)·γ²·G³·L_eff²·asinh(π²/2·|β₂|·L_eff,a·B²)/(π|β₂|L_eff,a)` (SCI, Poggiolini) + XCI por interferente |
| P5 | ⚠️ | §5 XT: cita “Lobato et al.” sem referência completa. A forma `P_XT = P_j·I_so·h·L` é a de acoplamento de potência com `h = 2κ²R/(βΛ)` (Koshiba; Hayashi et al., 2011) e vale para XT pequeno (`h·L ≪ 1`); para muitos vizinhos ou longas distâncias usar a forma exata `XT = n(1−e^{−(n+1)2hL})/(1+n·e^{−(n+1)2hL})`. Definir **`I_so`**: no código, é a fração de slots do interferente sobrepostos à vítima, implementada por cache por slot (razão efetiva `n_sobrep/n_interferente·hL`) | Referência completa + definição exata de `I_so` |
| P6 | ⚠️ | §6 Viabilidade só cita SNR; o modelo prevê também limiar de **XT** por modulação (campo `XT` do JSON), não aplicado (CR-05). Nas configurações fornecidas `XT_th = −(SNR_th + 10,08 dB)`; a origem do offset não é documentada | Documentar origem e aplicar |
| P7 | ➕ | Não define nº de slots (`n = ⌈R/(log₂M·f_slot)⌉ + G`), nem discute PDM/FEC (`polarizationModes`, `rateOfFEC` existem no JSON) (CR-10); nem o tratamento de banda de guarda e de regeneração | Acrescentar |
| P8 | ⚠️ | Valores de exemplo: `couplingCoefficient=0,012` m⁻¹ com `R=1 cm`, `Λ=45 µm`, `β=10⁷` dá `h=6,4·10⁻⁹ m⁻¹` → XT ≈ −31,9 dB por vizinho totalmente sobreposto em 100 km (muito severo comparado a MCFs de baixo XT, ordem de −50 dB/100 km). Se intencional (cenário “XT-limitado”), dizê-lo | Justificar ou ajustar |

## 5. `docs/implementation/`

| # | Doc | Tipo | Problema | Correção |
| :-: | :-- | :-: | :-- | :-- |
| I1 | `01_network_model` §1.4 | ❌ | Amplificador “utilizado para calcular o ASE” — não é (N1) | Corrigir |
| I2 | `01_network_model` §2.2 | ❌ | “QoT Repository” em `Circuit` — campos nunca escritos (N4) | Corrigir |
| I3 | `02_simulation_engine` §1.1 | ⚠️ | “FEL… rigorosamente na ordem temporal”: empates sem ordem definida (CR-13) | Especificar |
| I4 | `02_simulation_engine` §3.1 | ⚠️ | Garantia de “mesma semente ⇒ mesmos resultados” falha com `randomfit*` (CR-08) | Corrigir |
| I5 | `04_rmsca_logic` §2.1 | ❌ | “Modulation: Invoca `IModulationSelection`” (CR-03); omite QoT e regeneradores | Corrigir |
| I6 | `04_rmsca_logic` §1.2 | ⚠️ | “`maxReach` **superior** à distância” — código usa `≥` (`distância ≤ maxRange`) | Ajustar |
| I7 | `05_metrics_system` §1.2 | ⚠️ | `MetricsManager` “contém `BitRateBlocking` e `ResourceUtilization`” — hoje são 9 módulos | Atualizar |
| I8 | `05_metrics_system` §1.3 | ⚠️ | “Métricas omitidas são consideradas **ativas**” contradiz o “opt-in” de `formal/05` §4 e degrada desempenho (CR-09) | Decidir e alinhar |
| I9 | `05_metrics_system` §2.1 | ❌ | “Este método garante que a métrica seja insensível ao acaso… ocupação exata” — enviesada (CR-02, evidência empírica) | Corrigir |
| I10 | `05_metrics_system` §3.2 | ⚠️ | Cita aba `SimulationMetadata`, `ExternalFragmentation`, etc. — que não constam em `formal/06` | Unificar |
| I11 | `06_physical_layer_architecture` §4–5 | ❌ | “Predição O(S) independente do nº de conexões”: verdadeiro para o **próprio** canal; falso quando `activeQoTForOther` (O(N_ativos·S), CR-12). A comparação com SNetS1 (O(N²)) é afirmação sem fonte/medição | Qualificar e medir (plano L12) |
| I12 | `06_physical_layer_architecture` §3 | ⚠️ | “consistência validada via testes unitários” — há 1 teste de cache; sem teste de deriva/rede vazia (CR-14) | Acrescentar teste |
| I13 | `07_multithreading_and_checkpointing` | ⚠️ | Não menciona: ausência de *fingerprint* do setup, falha de uma réplica impede exportação, réplicas ausentes = 0.0 (CR-17); links `file:///Users/...` | Acrescentar |
| I14 | `implementation/README` | ⚠️ | `strictValidationEnabled` é um estático global, sem flag de CLI/JSON | Documentar |

## 6. Fórmulas que **faltam** na documentação (exigência do `GEMINI.md`)

Todas abaixo estão implementadas e devem ser derivadas/citadas em `formal/06` e `formal/07`:

* **Nº de slots**: `n = ⌈R / (log₂M · f_slot)⌉ + G` (atual) — proposta com PDM/FEC: `⌈R(1+FEC)/(N_pol·log₂M·f_slot)⌉ + G`.
* **BP por taxa**: `BP_bit = Σ R_bloqueada / Σ R_requisitada` (pós-*warm-up*); por causa, por par e por core (denominador = total requisitado, ⇒ as parcelas somam o geral).
* **Utilização (média temporal)**: `U = (1/T)·Σ_k U(t_k⁺)·(t_{k+1}−t_k)`; por link/core/rede com pesos iguais por (link, core).
* **Fragmentação externa**: `F_ext = 1 − L_max/L_livre`; **entropia**: `H = −Σ p_i ln p_i / ln S`, `p_i = l_i/L_livre` (definição própria — a literatura usa variantes; citar a fonte adotada); **relativa**: `F_rel(c) = Σ_{l_i<c} l_i / L_livre`; **horizontal**: `F_ext` do OR dos espectros ao longo do caminho.
* **Energia**: `P_estática = 100·N_EDFA + Σ_nós(85·grau + 100·(Tx+Rx) + 80·regen + 150)`; `P_circuito = 2·(1+n_regen)·(n_slots·1,683·(f_slot·log₂M/10⁹) + 91,333)`. Citar a origem (modelo de consumo de transponder/OXC) e as unidades.
* **SNR/ASE/NLI/XT**: ver P3–P5; **SNR com regeneração** = mínimo por segmento; **XT com regeneração** = máximo por segmento.
* **Little / carga**: `λ = load·μ`, `A_direção = load/2` (2 nós), `E[N] = λ_aceita/μ`.

## 7. Aderência à literatura — resumo

| Tópico | Referência canônica | Situação |
| :-- | :-- | :-- |
| EON / grade flexível de slots | Jinno et al. (2009), *IEEE Commun. Mag.* | ✅ conceito correto; falta fórmula de nº de slots com PDM/FEC (Christodoulopoulos et al., 2011) |
| RMSA/RMSCA, First/Last/Random/Exact Fit | Chatterjee et al. (2015, *survey*); Christodoulopoulos et al. | ⚠️ definições de EF/RF a precisar (A4) |
| k-caminhos mais curtos | Yen (1971) | ✅ estrutura correta (validar contra `networkx`) |
| Carga em Erlangs, M/M/c/c, Erlang-B | Kleinrock; Law & Kelton | ✅ **verificado empiricamente** |
| GN-model / NLI | Poggiolini (2012, 2014); Johannisson & Karlsson (2013) | ❌ não implementado como descrito (CR-01) |
| ASE em EDFA | Desurvire; Essiambre et al. (2010) | ✅ forma usual; premissas de ganho/nº de amplificadores a documentar |
| XT em fibras multi-núcleo | Koshiba et al. (2012); Hayashi et al. (2011); Klinkowski et al. | ⚠️ forma correta para XT pequeno; definir `I_so`, unidades e limiar (CR-05) |
| Métricas de fragmentação | Wright et al. (2015) e outros (entropia/“external/relative”) | ⚠️ definição de entropia própria; citar a variante adotada |
| Modelo de energia (transponder/OXC) | Vizcaíno et al.; Chowdhury et al. | ➕ constantes sem citação |

> Nota: as referências acima são pontos de partida para a revisão bibliográfica formal — recomenda-se confirmar títulos/anos exatos ao incorporá-las ao `formal/07`.
