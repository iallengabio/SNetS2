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
