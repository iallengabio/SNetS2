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
