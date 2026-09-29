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

Para outro FEC, rode `python3 scripts/compute_thresholds.py <BER_alvo> [margem_XT_dB]` (ex.: `3.8e-3` para HD-FEC de 7 %). Use a tabela gerada junto com o `rateOfFEC` correspondente. O `maxRange` de cada formato é independente desses limiares e deve ser revisado para refletir o mesmo FEC. O SNR é calculado na largura de sinal $B = (n - G)\,f_{slot}$, ou seja, sem os slots de guarda. Com regeneração, vale o **menor** SNR entre os segmentos transparentes.

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
