# Detalhamento Técnico: Módulo de Rede (Network Model)

Este documento descreve a implementação técnica das entidades físicas e lógicas que compõem o sistema de rede do SNetS2.

## 1. Entidades Físicas (Hardware)

### 1.1. `Spectrum` (com.snets2.model.Spectrum)
Gerencia a ocupação da grade espectral de um núcleo.
- **Implementação:** Utiliza `java.util.BitSet` onde `true` indica slot ocupado e `false` indica livre.
- **Validação de Integridade:** Impede a dupla alocação (alocar slot já ocupado) ou a liberação de slots já livres através de exceções disparadas quando a validação estrita está ativa.
- **Eficiência:** Operações de verificação de intervalo (`isRangeFree`) e alocação (`allocate`) são de alta performance.

### 1.2. `Core` (com.snets2.model.Core)
Representa um núcleo espacial dentro de uma fibra.
- **Atributos:** Possui um identificador único, uma instância de `Spectrum` e uma lista de `adjacentCores`.
- **Significância:** A lista de adjacência é a base para o cálculo de **Crosstalk (XT)**, permitindo modelar qualquer geometria de fibra (7-core, 19-core, etc.).

### 1.3. `Link` (com.snets2.model.Link)
Conecta dois nós `Node` na malha física.
- **Composição:** Contém um mapa de `Core`s e uma lista de `Amplifier`s.
- **Camada Física:** Armazena o comprimento (km), que impacta diretamente na atenuação e na seleção de modulação.

### 1.4. `Amplifier` (com.snets2.model.Amplifier)
Modela amplificadores ópticos (ex: EDFA) ao longo dos enlaces.
- **Parâmetros:** `gain` (dB), `noiseFigure` (dB), e consumo de potência.
- **Papel:** Utilizado para calcular o ruído **ASE (Amplified Spontaneous Emission)** acumulado e o consumo energético da rede.

### 1.5. `Node` (com.snets2.model.Node)
Representa um ROADM óptico.
- **Gerenciamento de Recursos:** Controla a contagem finita de Transmissores (`availableTx`), Receptores (`availableRx`) e Regeneradores (`availableRegenerators`).
- **Prevenção de Overflow/Underflow:** Valida se a liberação de recursos não excede a capacidade total do nó e se o consumo não ultrapassa a disponibilidade atual.
- **Bloqueio:** Se um nó destino não possui `Rx` livre ou o origem não possui `Tx`, a requisição é bloqueada antes mesmo do cálculo de espectro. De forma análoga, se nós intermediários escolhidos para regeneração não possuírem regeneradores livres, a conexão é bloqueada.

---

## 2. Entidades Lógicas e Estado

### 2.1. `ModulationFormat` (com.snets2.model.ModulationFormat)
Define as propriedades de um formato de modulação (ex: QPSK, 16QAM).
- **Mapeamento:** `m` (ordem de modulação), `snrThreshold` (dB), `crosstalkThreshold` (dB).
- **Cálculo de Banda:** Fornece o bit rate e a eficiência espectral ($\log_2(M)$).

### 2.2. `Circuit` (com.snets2.model.Circuit)
Representa uma conexão ativa (Lightpath).
- **Snapshot:** Armazena o caminho percorrido, o índice do núcleo em cada enlace, os slots alocados e a lista de nós intermediários atuando como regeneradores (`regeneratorNodes`).
- **QoT Repository:** Guarda as métricas de qualidade de transmissão (`aseNoise`, `nliNoise`, `crosstalk`) calculadas no momento do *setup*.

### 2.3. `ControlPlane` (com.snets2.model.ControlPlane)
O orquestrador central do estado da rede.
- **Single Source of Truth (SSoT):** Mantém a lista de circuitos ativos e referências a todos os nós e links.
- **Camada de Validação Estrita:** Antes de qualquer mutação, o método `establishCircuit` valida:
    - **Continuidades e Contiguidade:** Verifica se o mesmo bloco contíguo de slots está livre no núcleo especificado em todos os enlaces do caminho.
    - **Integridade de Core:** Garante que os índices de núcleos solicitados existam nos respectivos enlaces.
    - **Disponibilidade de Hardware:** Confirma se os nós de origem e destino possuem transceptores (Tx/Rx) livres, e se os nós intermediários no trajeto possuem regeneradores disponíveis.
- **Atomicidade:** As mudanças de estado só ocorrem se todas as validações acima passarem, consumindo os recursos de Tx, Rx e Regeneradores de forma atômica e evitando a corrupção do estado da simulação. Na desmontagem, o método `teardownCircuit` libera todos os slots, transceptores e regeneradores associados.
