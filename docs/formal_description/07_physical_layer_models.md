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

## 2. Potência do Sinal ($I_{ch}$)
A densidade espectral de potência do canal depende de como a configuração lida com o PSD (Power Spectral Density).

Se o PSD for **fixo** (`fixedPowerSpectralDensity = true`):
$$ I_{ch} = \frac{P_{laser}}{B_{ref}} $$
Onde $P_{laser}$ é a potência de referência do laser (ajustada para modos de polarização) e $B_{ref}$ é a largura de banda de referência.

Se o PSD for **variável**:
$$ I_{ch} = \frac{P_{circuit}}{B_{si}} $$
Onde $B_{si}$ é a largura de banda efetiva alocada para o circuito.

---

## 3. Ruído ASE ($I_{ASE}$)
O ruído ASE é gerado pelos amplificadores ópticos (EDFAs) utilizados para compensar a atenuação da fibra ao longo do enlace.
O ruído gerado por um único amplificador ($S_{ase}$) é tipicamente modelado em função de seu Ganho ($G$) e de sua Figura de Ruído ($NF$).

O ruído ASE total de um enlace é a soma das contribuições dos amplificadores de potência (Booster), amplificadores de linha (Line Amps) e pré-amplificadores (Pre-Amps), sendo cumulativo ao longo de todo o caminho óptico.

$$ I_{ASE\_enlace} = ASE_{booster} + N_l \times ASE_{line} + ASE_{pre} $$
Onde $N_l$ é o número de spans (vãos de fibra) completos no enlace.

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
