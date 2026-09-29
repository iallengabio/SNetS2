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
Código: `PhysicalLayerModel.effectiveBandwidth`, `circuitLaunchPower`, `signalPsd`.

A largura de banda efetiva (Nyquist) do circuito, sem bandas de guarda, é:
$$ B_{si} = \frac{R_b\,(1 + r_{FEC})}{N_{pol}\,\log_2 M} $$
onde $R_b$ é a taxa de bits requisitada, $r_{FEC}$ = `rateOfFEC`, $N_{pol}$ = `polarizationModes` (padrão 2) e $M$ a ordem da modulação (mesma definição de `Modulation.getBandwidthFromBitRate` no SNetS v1).

Seja $P_{ref}$ = `power` (dBm convertido para W) e $B_{ref}$ = `referenceBandwidthForPowerSpectralDensity` (Hz).

* **PSD variável** (`fixedPowerSpectralDensity = false`): todo circuito é lançado com a mesma potência, $P_i = P_{ref}$, e
$$ I_{ch} = \frac{P_{ref}}{B_{si}} $$
Circuitos mais largos têm, portanto, menor densidade espectral.
* **PSD fixa** (`fixedPowerSpectralDensity = true`): todos os circuitos mantêm a densidade de referência (lançamento com espectro "plano", hipótese dos modelos GN fechados [4, 5]):
$$ I_{ch} = \frac{P_{ref}}{B_{ref}}, \qquad P_i = I_{ch}\,B_{si} $$
A potência de lançamento $P_i$ passa a ser proporcional à largura de banda do circuito.

A potência $P_i$ assim obtida é a usada no cálculo de NLI, de crosstalk e da carga dos amplificadores (Seção 3.3), e é a registrada na métrica `PhysicalLayerStatistics`.

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
O SNetS2 suporta múltiplas formulações para o cálculo do NLI (como o modelo de Johannisson ou o modelo estendido de Habibi). A premissa central de ambos é que a interferência sofrida por um circuito $i$ depende da potência dos circuitos vizinhos $j$, da distância na frequência $\Delta f_{ij}$ entre eles, e das características da fibra.

### O Modelo (GN-Model simplificado)
O ruído NLI total num canal $i$ ($G_{NLI}$) é composto por:
1.  **Self-Channel Interference (SCI):** Interferência do sinal sobre si mesmo.
2.  **Cross-Channel Interference (XCI):** Interferência gerada pelos outros canais ativos $j$ *no mesmo núcleo* da fibra.

De forma genérica:
$$ G_{NLI\_i} = \mu \times (Termo_{SCI} + \sum_{j \neq i} Termo_{XCI}(\Delta f_{ij}, P_j)) $$
(onde $\mu$ engloba constantes de dispersão, não-linearidade e atenuação da fibra).

A característica vital do NLI é que **ele diminui drasticamente à medida que a distância espectral ($\Delta f_{ij}$) entre os canais aumenta.**

---

## 5. Crosstalk Inter-Core ($I_{XT}$)
Especificidade das Redes Ópticas Multicore (MC-EON), o Crosstalk ocorre quando fótons "vazam" de um núcleo espacial para um núcleo adjacente.

Baseado no modelo de Lobato et al., o acoplamento de potência $P_{xt}$ que um circuito $i$ (no núcleo central) recebe de um circuito $j$ (num núcleo vizinho adjacente) depende fortemente da sobreposição de espectro:

$$ P_{XT\_ij} = P_j \times I_{soij} \times h \times L $$

Onde:
*   $P_j$: Potência do circuito vizinho.
*   $I_{soij}$: Índice de Sobreposição Espectral (porcentagem de slots que compartilham a mesma frequência).
*   $h$: Coeficiente de acoplamento de potência intrínseco da fibra.
*   $L$: Comprimento físico do enlace.

O ruído total de Crosstalk em $i$ é a soma de todos os $P_{XT}$ recebidos de todos os núcleos adjacentes.
A conversão para densidade para o cálculo do SNR é dada por:
$$ I_{XT} = \frac{\sum P_{XT}}{B_{si}} $$

---

## 6. Viabilidade de Conexão
Para que o algoritmo RMSCA aceite uma alocação, o OSNR calculado para o circuito proposto deve satisfazer:

$$ OSNR_{dB} = 10 \times \log_{10}(SNR) \ge SNR_{threshold\_mod} $$

Onde $SNR_{threshold\_mod}$ é o limiar de tolerância específico do formato de modulação selecionado.

---

## 7. Chaves removidas em relação ao SNetS v1
* `physicalLayerModel` (Johannisson × Habibi): o SNetS2 usa um único modelo de NLI, compatível com o cache incremental (Seção 4).
* `crosstalkModel` (XT separado × junto): o crosstalk é sempre somado aos demais ruídos no SNR (equivalente a `XT_TOGETHER` no v1); a classificação do bloqueio por crosstalk é feita recalculando o SNR sem $I_{XT}$.
* `typeOfTestQoT` (limiar de SNR × BER): a admissibilidade é sempre verificada pelo limiar de SNR da modulação (Seção 6).

---

## Referências
O modelo da camada física do SNetS2 é derivado do SNetS v1, cuja versão mais recente é o artefato do SBRC 2026: [alexandrefontinele/SNetS-SDM-SBRC26](https://github.com/alexandrefontinele/SNetS-SDM-SBRC26) (classes `network.PhysicalLayer`, `network.Amplifier`, `network.Crosstalk` e `simulationControl.parsers.PhysicalLayerConfig`).

1. E. Desurvire, *Erbium-Doped Fiber Amplifiers: Principles and Applications*, Wiley, 1994.
2. G. P. Agrawal, *Fiber-Optic Communication Systems*, 4ª ed., Wiley, 2010.
3. H. A. Pereira, D. A. R. Chaves, C. J. A. Bastos-Filho, J. F. Martins-Filho, "OSNR model to consider physical layer impairments in transparent optical networks", *Photonic Network Communications*, vol. 18, pp. 137–149, 2009.
4. P. Poggiolini, "The GN Model of Non-Linear Propagation in Uncompensated Coherent Optical Systems", *J. Lightwave Technol.*, vol. 30, no. 24, 2012.
5. P. Johannisson, E. Agrell, "Modeling of Nonlinear Signal Distortion in Fiber-Optic Networks", *J. Lightwave Technol.*, vol. 32, no. 23, 2014.

