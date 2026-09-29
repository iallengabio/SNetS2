# SNetS2: Modelos da Camada Física (OSNR e Crosstalk)

## 1. Visão Geral
A avaliação da Qualidade de Transmissão (QoT) no SNetS2 é o fator determinante para a viabilidade de um circuito óptico. Um circuito só pode ser estabelecido se o Sinal (Signal) em relação aos Ruídos e Interferências (Noise) for superior ao limiar exigido pelo formato de modulação escolhido.

A relação sinal-ruído óptica (OSNR) linear é definida por:

$$ SNR = \frac{I_{ch}}{I_{ASE} + I_{NLI} + I_{XT}} $$

Onde:
*   $I_{ch}$: Densidade espectral de potência do sinal.
*   $I_{ASE}$: Ruído de Emissão Espontânea Amplificada (Linear).
*   $I_{NLI}$: Interferência Não-Linear intra-core (Linear).
*   $I_{XT}$: Crosstalk inter-core (Linear).

---

## 2. Potência de Lançamento e Densidade Espectral ($I_{ch}$)
Chaves: `power`, `fixedPowerSpectralDensity`, `referenceBandwidthForPowerSpectralDensity`, `rateOfFEC`, `polarizationModes`.
Código: `PhysicalLayerModel.signalBandwidth`, `circuitLaunchPower`.

A largura de banda de sinal do circuito é a faixa alocada sem os slots de guarda, $B_i = (n - G)\,f_{slot}$, onde $n$ é o número de slots alocados, $G$ = `guardBand` e $f_{slot}$ = `bvtSpectralWidth`. O número de slots já incorpora `rateOfFEC` e `polarizationModes` (`SlotCalculator`).

Seja $P_{ref}$ = `power` (dBm convertido para W) e $B_{ref}$ = `referenceBandwidthForPowerSpectralDensity` (Hz).

* **PSD variável** (`fixedPowerSpectralDensity = false`): todo circuito é lançado com a mesma potência, $P_i = P_{ref}$, e
$$ I_{ch} = \frac{P_{ref}}{B_i} $$
Circuitos mais largos têm, portanto, menor densidade espectral.
* **PSD fixa** (`fixedPowerSpectralDensity = true`): todos os circuitos mantêm a densidade de referência (lançamento com espectro "plano", hipótese dos modelos GN fechados [4, 5]):
$$ I_{ch} = \frac{P_{ref}}{B_{ref}}, \qquad P_i = I_{ch}\,B_i $$
A potência de lançamento $P_i$ passa a ser proporcional à largura de banda do circuito.

A potência $P_i$ assim obtida é a usada no cálculo de NLI (SCI e XCI), de crosstalk e da carga dos amplificadores (Seção 3.3), e é a registrada na métrica `PhysicalLayerStatistics`.

---

## 3. Ruído ASE ($I_{ASE}$)
Chaves: `noiseFigureOfOpticalAmplifier`, `amplificationFrequency`, `constantOfPlanck`, `spanLength`, `fiberLoss`, `switchInsertionLoss`, `typeOfAmplifierGain`, `powerSaturationOfOpticalAmplifier`, `noiseFactorModelParameterA1`, `noiseFactorModelParameterA2`.
Código: `PhysicalLayerModel.calculateLinkAse`, `amplifierGain`, `amplifierAse`.

### 3.1. Cadeia de amplificadores de um enlace
Seguindo o SNetS v1 (`PhysicalLayer.computeSNRSegment`), cada enlace de comprimento $L$ possui:

| Amplificador | Quantidade | Ganho nominal $G_0$ (dB) |
| :--- | :--- | :--- |
| Booster (compensa as perdas do ROADM: demux + switch + mux) | 1 | $3 \times L_{sss}$ (`switchInsertionLoss`) |
| Linha (um por vão) | $N_l = \max\left(0, \lceil L/L_{span} - 1 \rceil\right)$ | $\alpha\,L_{span}$ |
| Pré-amplificador (último trecho) | 1 | $\alpha\,(L - N_l L_{span})$ |

com $\alpha$ = `fiberLoss` (dB/km) e $L_{span}$ = `spanLength` (km).

### 3.2. ASE de um amplificador
A densidade espectral de ASE (duas polarizações) na saída de um amplificador é [1, 2]:
$$ S_{ase} = F\,h\,\nu\,(G - 1) $$
onde $h$ = `constantOfPlanck`, $\nu$ = `amplificationFrequency`, $G$ o ganho linear e $F$ o fator de ruído.

### 3.3. Tipo de ganho (`typeOfAmplifierGain`)
* **0 — Ganho fixo:** $G = G_0$ e $F = NF$ (`noiseFigureOfOpticalAmplifier`, linear). O ASE do enlace independe da carga e é pré-calculado uma única vez (`Link.staticAseNoise`):
$$ I_{ASE,enlace} = S_{ase}^{booster} + N_l\,S_{ase}^{linha} + S_{ase}^{pre} $$
* **1 — Ganho saturado:** o ganho é comprimido pela potência total de entrada $P_{in}$ e o fator de ruído cresce com ela (modelo de Pereira et al. [3], também usado em `Amplifier.getGainSaturated`/`getFamp` do SNetS v1):
$$ G = \max\left(1,\ \frac{G_0}{1 + G_0 P_{in}/P_{sat}}\right), \qquad F = NF\left(1 + A_1 - \frac{A_1}{1 + P_{in}/A_2}\right) $$
com $P_{sat}$ = `powerSaturationOfOpticalAmplifier` (dBm), $A_1$ = `noiseFactorModelParameterA1` (adimensional) e $A_2$ = `noiseFactorModelParameterA2` (W, mesma unidade de $P_{in}$ no SNetS v1).

  * **$P_{sat}$ é a potência de saturação de saída** do amplificador. Na expressão acima, quando a potência de saída não saturada $G_0 P_{in}$ iguala $P_{sat}$, o ganho cai à metade (compressão de 3 dB). A carga é a potência total **do núcleo** (todos os canais que atravessam o amplificador), e não a de um canal.
  * **Valor dos experimentos: 23 dBm** (antes 16 dBm, issue #17). EDFAs de linha comerciais para a banda C têm potência de saída total da ordem de 20–23 dBm. O valor anterior vem de simuladores de redes WDM com poucos canais: o SIMTON [6], dos mesmos autores de [3], usa 19 dBm (e compara 16 × 19 dBm) com 36 canais de 0 dBm em grade de 100 GHz. Na grade dos experimentos (320 slots de 12,5 GHz, 4 THz), um núcleo cheio de canais de 0 dBm soma de 12 dBm (800 Gbps em 4QAM, 15 canais) a 22 dBm (100 Gbps em 32/64QAM, 160 canais). Com 16 dBm, um núcleo com 40 canais de 0 dBm já está no ponto de 3 dB (V&V, E7e). Com 23 dBm, a carga cheia fica abaixo do ponto de 3 dB: a compressão do primeiro amplificador de linha vai de ~0,3 dB (12 dBm) a ~2,5 dB (22 dBm). É o regime esperado de um amplificador de linha dimensionado para a banda C inteira. Os experimentos usam ganho fixo (`typeOfAmplifierGain = 0`), em que $P_{sat}$ não entra no cálculo. O valor só vale ao ligar o ganho saturado; nesse caso, recalcule os `maxRange` (§6.2), pois a compressão reduz muito o alcance.
  * **Unidade de $A_1$ e $A_2$ (não confirmada):** o SNetS2 segue o SNetS v1 (`Amplifier.getFamp`), que usa $P_{in}$ em W sem declarar a unidade de $A_2$; assume-se, portanto, $A_2$ em W e $A_1$ adimensional. **A unidade não foi confirmada no artigo original [3]**, cujo texto integral não estava acessível na revisão; os valores 100 e 4 também vêm do v1. Isso importa: com $A_2 = 4$ W e $P_{in}$ da ordem de mW, $P_{in}/A_2 \ll 1$ e $F \approx NF\,(1 + A_1 P_{in}/A_2)$ fica praticamente igual a $NF$ (+2,5 % para $P_{in} = 1$ mW). Se $A_2$ estivesse em mW, o mesmo $P_{in}$ multiplicaria $F$ por cerca de 21. Confira a unidade na fonte antes de usar o ganho saturado em estudos que dependam do aumento do ruído com a carga.

  A carga $P_{tot}$ de um núcleo é a soma das potências de lançamento $P_i$ dos circuitos ativos nele (`Core.totalLaunchPower`, incluindo o circuito candidato). Como o ganho comprimido também atenua o sinal, a cadeia é avaliada em cascata ($1/OSNR = \sum_k 1/OSNR_k$ [3]). Sendo $\rho_k$ a razão entre a potência do canal na saída do amplificador $k$ e a nominal ($\rho_0 = 1$):
$$ P_{in,k} = \frac{\rho_{k-1}\,P_{tot}}{G_{0,k}}, \qquad \rho_k = \rho_{k-1}\frac{G_k}{G_{0,k}}, \qquad I_{ASE,enlace} = \sum_k \frac{S_{ase,k}}{\rho_k} $$
  Assume-se que a potência é reequalizada em cada ROADM ($\rho$ reinicia em 1 a cada enlace). Com carga desprezível o resultado coincide com o do ganho fixo.

> **Diferenças em relação ao SNetS v1 (por consistência com a literatura):**
> 1. O v1 reduz o ASE sob saturação mas mantém o sinal no nível nominal, de modo que o SNR *melhoraria* com o aumento da carga. O SNetS2 propaga a compressão do sinal na cascata (acima), fazendo o SNR degradar com a carga.
> 2. O v1 só aplica a compressão quando $G_0 P_{in} > P_{sat}$, o que gera um salto no ganho nesse ponto; o SNetS2 aplica a expressão de forma contínua.
> 3. O v1 usa $G$ no ASE saturado ($0{,}5\,h\nu F G$) e $G-1$ no ganho fixo; o SNetS2 usa $F h \nu (G-1)$ em ambos os casos, e a carga do amplificador é a potência total (não por polarização).

O ASE total de um caminho é a soma dos enlaces do segmento transparente (entre regeneradores).

---

## 4. Interferência Não-Linear ($I_{NLI}$)
O SNetS2 usa o **modelo GN incoerente** em forma fechada. Para um canal vítima $i$ com PSD total (dupla polarização) $G_i = P/B_i$ e largura $B_i$, cada vão contribui com:

$$ G_{NLI,i} = \mu \, G_i \left[ G_i^2 \,\mathrm{asinh}\!\left(\rho B_i^2\right) + \sum_{j \neq i} G_j^2 \ln\!\frac{|\Delta f_{ij}| + B_j/2}{|\Delta f_{ij}| - B_j/2} \right] $$

$$ \mu = \frac{8}{27}\,\frac{\gamma^2 L_{eff}^2}{\pi |\beta_2| L_{eff,a}}, \qquad \rho = \frac{\pi^2}{2} |\beta_2| L_{eff,a}, \qquad L_{eff} = \frac{1-e^{-\alpha L_s}}{\alpha}, \quad L_{eff,a} = \frac{1}{\alpha} $$

* $\alpha$: atenuação de potência (1/m, a partir de `fiberLoss` em dB/km); $L_s$ = `spanLength`; $\gamma$ = `fiberNonlinearity` (1/(W·m)); $|\beta_2| = D\lambda^2/(2\pi c)$ com $D$ = `fiberDispersion` (s/m²).
* **SCI** (1º termo): forma fechada do GN de Poggiolini (2012) para um canal isolado.
* **XCI** (somatório, forma de Johannisson & Karlsson): limite de alta dispersão do GN, consistente com o SCI. Para canais iguais e contíguos, SCI + ΣXCI tende ao SCI da banda ocupada inteira (verificado em `PhysicalLayerMagnitudeTest`). Só entram os canais **do mesmo núcleo** do enlace.
* O decaimento do XCI com $\Delta f$ é lento, $\approx B_j/|\Delta f|$, e dominado pelos vizinhos espectrais próximos.
* Vãos somam-se **incoerentemente**: $I_{NLI,\,enlace} = N_{vãos}\cdot G_{NLI,i}$, com $N_{vãos} = \max(1, \lceil L/L_s\rceil)$.
* Consequência verificável: para um canal isolado, o SNR em função da potência tem um máximo em $P_{opt}$, onde $I_{NLI} = I_{ASE}/2$.

---

## 5. Crosstalk Inter-Core ($I_{XT}$)
Especificidade das Redes Ópticas Multicore (MC-EON), o Crosstalk ocorre quando potência "vaza" de um núcleo espacial para um núcleo adjacente.

O modelo é de acoplamento de potência para XT pequeno ($hL \ll 1$). O coeficiente é $h = 2\kappa^2 R/(\beta \Lambda)$, com $\kappa$ = `couplingCoefficient`, $R$ = `bendingRadius`, $\beta$ = `propagationConstant` e $\Lambda$ = `corePitch`. Um circuito $j$ num núcleo adjacente injeta, em cada slot que ocupa, a densidade

$$ I_{XT,j} = \frac{P_j\, h\, L}{B_j} $$

em que $L$ é o comprimento do enlace e $B_j$ a largura de sinal de $j$. As contribuições somam-se sobre os vizinhos e os enlaces. Para a vítima $i$, a média sobre os seus slots dá a sobreposição espectral (índice $I_{so}$). A **razão de crosstalk** adimensional é

$$ XT_i = \frac{\sum_{enlaces} \overline{I_{XT}}}{I_{ch,i}}, \qquad I_{ch,i} = \frac{P}{B_i} $$

Para um único vizinho totalmente sobreposto e de mesma largura, $XT = hL$. Com os parâmetros de exemplo ($h = 6{,}4\cdot10^{-9}$ m⁻¹), isso dá −31,9 dB em 100 km. O XT entra no SNR como ruído ($I_{XT}$) e é também comparado com o limiar de XT da modulação (§6). Com regeneração, vale o maior XT entre os segmentos transparentes.

---

## 6. Viabilidade de Conexão
Para que o algoritmo RMSCA aceite uma alocação, o circuito proposto (e, com `activeQoTForOther`/`activeXTForOther`, cada circuito ativo) deve satisfazer:

$$ SNR_{dB} = 10 \log_{10}(SNR) \ge SNR_{th}(mod) \qquad\text{e}\qquad XT_{dB} = 10\log_{10}(XT) \le XT_{th}(mod) \;\;(\text{se } activeXT) $$

onde $SNR_{th}$ (campo `SNR`) e $XT_{th}$ (campo `XT`) são os limiares do formato de modulação. O limiar de SNR é **pré-FEC**: deve corresponder ao BER máximo que o FEC configurado em `rateOfFEC` corrige (ver `05_experiment_setup.md`, §3).

### 6.1. Origem dos limiares (`SNR` e `XT` das modulações)
Os limiares são **dados de entrada** do JSON. Os valores fornecidos nos experimentos do repositório foram calculados por `scripts/compute_thresholds.py` para um **SD-FEC com BER pré-FEC corrigível de $2{,}4\cdot10^{-2}$**. Esse é o valor usual para códigos de ~20 % de overhead e, portanto, conservador para os 25 % configurados.

* **SNR:** menor SNR (por símbolo, na largura de sinal) cujo BER pré-FEC não excede o alvo. Usa-se a aproximação para M-QAM com codificação Gray:
  $$ BER \approx \frac{4}{\log_2 M}\left(1-\frac{1}{\sqrt{M}}\right) Q\!\left(\sqrt{\frac{3\,SNR}{M-1}}\right) $$
  A aproximação é da ordem de grandeza exata para QAM quadrada e padrão para 8QAM e 32QAM.
* **XT:** $XT_{th} = -(SNR_{th} + 10{,}08\ \text{dB})$. O crosstalk deve ficar ~10 dB abaixo do nível de ruído admitido pelo limiar de SNR, o que corresponde a uma penalidade de ~0,4 dB. É a mesma convenção dos arquivos originais.

| Formato | SNR (dB) | XT (dB) | Valores anteriores (HD-FEC, BER ≈ 1–3·10⁻³) |
| :-- | :-: | :-: | :-- |
| 4QAM | 5,92 | −16,00 | 8,95 / −19,03 |
| 8QAM | 9,32 | −19,40 | 13,15 / −23,23 |
| 16QAM | 12,34 | −22,42 | 15,49 / −25,57 |
| 32QAM | 15,22 | −25,30 | 18,51 / −28,59 |
| 64QAM | 18,02 | −28,10 | 21,28 / −31,36 |

Para outro FEC, rode `python3 scripts/compute_thresholds.py <BER_alvo> [margem_XT_dB]` (ex.: `3.8e-3` para HD-FEC de 7 %). Use a tabela gerada junto com o `rateOfFEC` correspondente e recalcule os `maxRange` com o mesmo FEC (§6.2). O SNR é calculado na largura de sinal $B = (n - G)\,f_{slot}$, ou seja, sem os slots de guarda. Com regeneração, vale o **menor** SNR entre os segmentos transparentes.

### 6.2. Origem do alcance (`maxRange` das modulações)
O `maxRange` é usado pela seleção de modulação por distância (`distance-adaptive`) para descartar formatos antes da verificação de QoT (`04_rmsca_algorithms.md`). Para que ele e os limiares de SNR (§6.1) descrevam o **mesmo sistema**, os valores dos experimentos são calculados com o próprio modelo do SNetS2, pela ferramenta `scripts/compute_reach.sh` (classe `com.snets2.verification.ReachCalculator`, em escopo de teste):

```bash
scripts/compute_reach.sh experiments/experiment01/setup.json            # pior caso entre as taxas de traffic.bitRates
scripts/compute_reach.sh experiments/experiment01/setup.json --bitRate 100 --step 10
```

A ferramenta lê o `setup.json` (`physicalLayer`, `simulation.totalSlots`, `traffic.bitRates` e os limiares das modulações), imprime a tabela abaixo e o trecho JSON `"modulations"` com os novos `maxRange`.

**Carga de referência.** Um enlace de comprimento $L$ (booster, $N_l$ amplificadores de linha e pré-amplificador, §3.1), ou seja, um único salto transparente, com um núcleo de `totalSlots` slots **totalmente preenchido** por canais iguais ao canal em teste: $\lfloor$`totalSlots`$/n\rfloor$ alocações contíguas de $n$ slots (`SlotCalculator`, com banda de guarda). O canal em teste é o **central**, que sofre o maior XCI. Ele é avaliado como candidato por `PhysicalLayerModel.predictSNR`, com todos os demais estabelecidos. Entram ASE (com a carga, se o ganho for saturado), SCI e o XCI de todos os vizinhos, com a potência, o tipo de PSD, o FEC (`rateOfFEC`), `polarizationModes`, `guardBand`, `bvtSpectralWidth` e `spanLength` configurados. O crosstalk inter-núcleo **não** entra no alcance: tem limiar próprio (`XT`) e depende do arranjo e da ocupação dos núcleos, não só do comprimento.

**Alcance.** É o maior $L$, múltiplo de 10 km (`--step`), com $SNR(L) \ge SNR_{th}$. O SNR não cresce com $L$: o ASE cresce continuamente, e o NLI salta a cada novo vão. Com `spanLength` múltiplo do passo, as fronteiras de vão estão na grade, e o arredondamento é sempre para baixo. A busca é uma bisseção sobre os múltiplos do passo, até 100 000 km.

**Taxa de referência: o pior caso entre as taxas configuradas.** O `maxRange` é um valor único por formato, enquanto o SNR depende da taxa. Com PSD variável, todos os circuitos têm a mesma potência. Canais largos (taxas altas) têm PSD menor e são limitados pelo ASE. Canais estreitos (taxas baixas em formatos densos) têm PSD alta e somam muita potência no núcleo cheio (160 canais de 0 dBm = 22 dBm), logo são limitados pelo NLI. Nenhuma taxa é o pior caso para todos os formatos. Tomar o menor alcance entre as taxas garante que **qualquer requisição configurada**, num caminho de um salto até `maxRange`, atende ao limiar mesmo com o núcleo cheio. Um valor maior (ex.: o de 100 Gbps para 4QAM, 12 960 km) faria a seleção por distância escolher formatos que a verificação de QoT rejeitaria para outras taxas.

**Resultado para os experimentos do repositório** (0 dBm, PSD variável, SD-FEC 25 %, 2 polarizações, 1 slot de guarda, 320 slots de 12,5 GHz, vãos de 80 km, ganho fixo). Entre parênteses: slots por circuito (com guarda) e canais no núcleo.

| Formato | SNR (dB) | 100 Gbps | 200 Gbps | 400 Gbps | 800 Gbps | **`maxRange`** (km) | Anterior (HD-FEC) |
| :-- | :-: | :-: | :-: | :-: | :-: | :-: | :-: |
| 4QAM | 5,92 | 12 960 (4, 80) | 14 710 (6, 53) | 9 790 (11, 29) | 5 110 (21, 15) | **5 110** | 5 000 |
| 8QAM | 9,32 | 4 000 (3, 106) | 6 710 (5, 64) | 5 820 (8, 40) | 3 270 (15, 21) | **3 270** | 2 500 |
| 16QAM | 12,34 | 2 000 (3, 106) | 2 930 (4, 80) | 3 330 (6, 53) | 2 210 (11, 29) | **2 000** | 1 250 |
| 32QAM | 15,22 | 400 (2, 160) | 1 000 (3, 106) | 1 680 (5, 64) | 1 340 (9, 35) | **400** | 625 |
| 64QAM | 18,02 | 160 (2, 160) | 480 (3, 106) | 870 (5, 64) | 740 (8, 40) | **160** | 312 |

Observações:
* O alcance de um canal **isolado** (V&V, E7c) é de 4 a 7 vezes maior que os valores anteriores. Com o núcleo cheio, o XCI dos vizinhos reduz fortemente o alcance dos canais estreitos. O pior caso de 16QAM a 64QAM é o de 100 Gbps (1 ou 2 slots de sinal a 0 dBm, PSD acima da ótima), e o de 4QAM e 8QAM é o de 800 Gbps (21 e 15 slots, ASE dominante).
* **Multi-salto:** cada enlace adicional do caminho acrescenta um booster ($3 L_{sss}$ = 15 dB de ganho), cujo ASE equivale a cerca de um vão. Um caminho de vários saltos com comprimento total $\le$ `maxRange` pode, portanto, não atender ao limiar. Nesse caso a verificação de QoT (ainda ativa) bloqueia a requisição, e o bloqueio aparece como QoT. Com carga parcial, o XCI é menor que o de referência, e o alcance real é maior.
* O `maxRange` depende de `power`, `fixedPowerSpectralDensity`, `rateOfFEC`, `polarizationModes`, `guardBand`, `bvtSpectralWidth`, `totalSlots`, `spanLength`, dos parâmetros da fibra e dos amplificadores (inclusive `typeOfAmplifierGain` e `powerSaturationOfOpticalAmplifier`), das taxas de `traffic.bitRates` e dos limiares `SNR`. **Ao mudar qualquer um deles, recalcule-o.** O teste `ReachCalculatorTest` falha se o `maxRange` de algum `experiments/*/setup.json` exceder o alcance calculado.
* Com ganho saturado (`typeOfAmplifierGain = 1`) e $P_{sat}$ = 23 dBm, o mesmo cálculo dá 2 380 / 1 590 / 1 100 / 320 / 160 km (4QAM → 64QAM).

---

## 7. Chaves removidas em relação ao SNetS v1
* `physicalLayerModel` (Johannisson × Habibi): o SNetS2 usa um único modelo de NLI, compatível com o cache incremental (Seção 4).
* `crosstalkModel` (XT separado × junto): o crosstalk é sempre somado aos demais ruídos no SNR (equivalente a `XT_TOGETHER` no v1); a classificação do bloqueio por crosstalk é feita recalculando o SNR sem $I_{XT}$ e comparando o XT com o limiar da modulação (Seção 6).
* `typeOfTestQoT` (limiar de SNR × BER): a admissibilidade é sempre verificada pelo limiar de SNR da modulação (Seção 6).

---

## Referências
O modelo da camada física do SNetS2 é derivado do SNetS v1, cuja versão mais recente é o artefato do SBRC 2026: [alexandrefontinele/SNetS-SDM-SBRC26](https://github.com/alexandrefontinele/SNetS-SDM-SBRC26) (classes `network.PhysicalLayer`, `network.Amplifier`, `network.Crosstalk` e `simulationControl.parsers.PhysicalLayerConfig`).

1. E. Desurvire, *Erbium-Doped Fiber Amplifiers: Principles and Applications*, Wiley, 1994.
2. G. P. Agrawal, *Fiber-Optic Communication Systems*, 4ª ed., Wiley, 2010.
3. H. A. Pereira, D. A. R. Chaves, C. J. A. Bastos-Filho, J. F. Martins-Filho, "OSNR model to consider physical layer impairments in transparent optical networks", *Photonic Network Communications*, vol. 18, pp. 137–149, 2009.
4. P. Poggiolini, "The GN Model of Non-Linear Propagation in Uncompensated Coherent Optical Systems", *J. Lightwave Technol.*, vol. 30, no. 24, 2012.
5. P. Johannisson, E. Agrell, "Modeling of Nonlinear Signal Distortion in Fiber-Optic Networks", *J. Lightwave Technol.*, vol. 32, no. 23, 2014.
6. D. A. R. Chaves, H. A. Pereira, C. J. A. Bastos-Filho, J. F. Martins-Filho, "SIMTON: A Simulator for Transparent Optical Networks", *Journal of Communication and Information Systems* (SBrT), [jcis.sbrt.org.br/jcis/article/view/142](https://jcis.sbrt.org.br/jcis/article/view/142) (Tabela de parâmetros: amplificador com potência de saturação de saída de 19 dBm e NF de 5 dB; 36 canais de 0 dBm em grade de 100 GHz).
