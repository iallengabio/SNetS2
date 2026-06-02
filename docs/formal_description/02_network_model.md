# SNetS2: Modelagem do Sistema (Network Model)

## 1. Visão Geral
O Módulo de Sistema do SNetS2 é responsável por manter o estado atual da rede e fornecer as interfaces para que os algoritmos de alocação de recursos (RMSCA - Routing, Modulation, Spectrum, and Core Allocation) possam operar. Ele modela a infraestrutura física (Mesh) e a inteligência lógica (Plano de Controle).

Este módulo é essencialmente reativo: ele não "age" por conta própria, mas responde a comandos disparados pelo Módulo de Eventos (ex: "verificar disponibilidade", "alocar recursos", "liberar recursos").

---

## 2. Entidades de Rede (Hierarquia de Hardware)

### 2.1. Nó (Node)
Representa um ROADM (Reconfigurable Optical Add-Drop Multiplexer).
*   **Capacidade de Add/Drop:** Gerencia a inserção e extração de sinais locais.
*   **Recursos:** Agrega coleções de Transmissores, Receptores e Regeneradores.
*   **Estado:** O nó rastreia o consumo atual de cada tipo de recurso.

### 2.2. Transmissor e Receptor (Transceiver - BVT)
Os BVTs (Bandwidth Variable Transceivers) são os componentes que realizam a interface entre o domínio elétrico e o óptico.
*   **Transmissor (Tx):** Responsável por gerar o sinal óptico, definir a modulação e ocupar uma fatia do espectro.
*   **Receptor (Rx):** Realiza a detecção coerente do sinal no destino.
*   **Modelagem:** No simulador, são tratados como unidades discretas. Uma conexão consome 1 Tx na origem e 1 Rx no destino.

### 2.3. Regenerador (Regenerator)
Dispositivo capaz de realizar a conversão O-E-O (Óptico-Elétrico-Óptico) para restaurar a integridade do sinal.
*   **Uso:** Acionado quando a distância ou as interferências (XT/NLI) impedem que o sinal chegue ao destino com a QoT mínima exigida.
*   **Localização:** São recursos finitos e compartilhados dentro dos nós (ROADM).
*   **Custo:** O uso de um regenerador consome recursos espectrais extras e adiciona latência.

### 2.4. Enlace (Link)
Conexão física entre dois nós.
*   **Características:** Comprimento (km) e parâmetros físicos (perda, não-linearidade).
*   **Estrutura SDM:** Contém um ou mais núcleos (Cores).
*   **Spans:** Um enlace é composto por múltiplos vãos de fibra (*spans*), geralmente separados por amplificadores.

### 2.5. Amplificador (Amplifier)
Geralmente amplificadores de fibra dopada com érbio (EDFA).
*   **Função:** Compensar a atenuação da fibra ao longo do enlace.
*   **Impacto Físico:** Embora restaurem a potência, os amplificadores inserem ruído de emissão espontânea amplificada (**ASE Noise**), que degrada a relação sinal-ruído óptica (OSNR).
*   **Modelagem:** O simulador considera o ganho e o fator de ruído (*Noise Figure*) de cada amplificador para o cálculo de QoT.

### 2.6. Núcleo (Core)
A unidade de divisão espacial dentro de uma fibra multicore.
*   **Geometria:** Cada núcleo conhece seus `adjacentCores`. Esta informação é vital para o cálculo de **Crosstalk (XT)**.
*   **Grade Espectral:** Cada núcleo possui uma grade de slots contíguos.

### 2.7. Espectro (Spectrum)
Representado como um array ou BitSet de slots.
*   **Slot:** A menor unidade de alocação de largura de banda.
*   **Estado:** Cada slot pode estar `Livre` ou `Ocupado`.

---

## 3. Plano de Controle (Control Plane)
O Plano de Controle é a ponte entre os eventos e os algoritmos. Ele gerencia o ciclo de vida das requisições na rede.

### 3.1. Algoritmos de Atribuição (RMSCA)
O Plano de Controle invoca uma cadeia de algoritmos para atender uma requisição:
1.  **Roteamento (Routing):** Encontra um ou mais caminhos físicos entre origem e destino.
2.  **Atribuição de Core (Core Assignment):** Escolhe qual núcleo espacial será utilizado.
3.  **Seleção de Modulação (Modulation Selection):** Escolhe o formato de modulação com base na distância e QoT.
4.  **Alocação de Espectro (Spectrum Allocation):** Encontra slots contíguos e contínuos (continuidade de slot/core/modal).

### 3.2. Gerenciamento de Conexões (Circuit/Lightpath)
Quando uma requisição é atendida com sucesso, o Plano de Controle cria um objeto `Circuit` que armazena:
*   Caminho percorrido (lista de nós e links).
*   Núcleo utilizado em cada link.
*   Índices dos slots alocados.
*   Modulação e taxa de bits.
*   Recursos de hardware consumidos (Tx, Rx, Regeneradores).
*   **Métricas de Qualidade de Transmissão (QoT):** Nível de ruído (ASE/NLI) e Crosstalk (XT) calculado no momento do estabelecimento.

### 3.3. Repositório de Circuitos Ativos e Cache de Estado
Para garantir a performance exigida (evitar cálculos repetitivos de Camada Física), o Plano de Controle mantém um **Repositório de Circuitos Ativos**.
*   **Persistência de Dados:** Toda informação de QoT é armazenada no objeto `Circuit`. Algoritmos e eventos podem consultar a qualidade de um circuito ativo sem disparar um novo cálculo matemático complexo.
*   **Visibilidade para Algoritmos:** Os algoritmos de RMSCA têm acesso total a este repositório. Isso permite, por exemplo, que um algoritmo de alocação considere o impacto de uma nova requisição nos circuitos que já estão "vivos" na rede.

---

## 4. O Plano de Controle como Provedor de Informações
O Plano de Controle atua como o *Single Source of Truth* (SSoT) para os algoritmos de alocação de recursos, fornecendo:
1.  **Estado dos Recursos:** Mapas de ocupação de slots por link/core e disponibilidade de hardware nos nós.
2.  **Estado dos Circuitos:** Lista de conexões ativas e seus respectivos parâmetros técnicos.
3.  **Topologia Dinâmica:** Visão atualizada da rede (útil em casos de falhas ou reconfigurações).

---

## 5. Gerenciamento de Estado
A alteração de estado ocorre em dois momentos principais disparados pelo Módulo de Eventos:

1.  **Estabelecimento (Setup):**
    *   Marca os slots como `Ocupados`.
    *   Decrementa Tx/Rx/Regeneradores nos nós.
    *   Atualiza estatísticas de ocupação.
2.  **Desestabelecimento (Teardown):**
    *   Marca os slots como `Livres`.
    *   Incrementa Tx/Rx/Regeneradores nos nós.
    *   Limpa o estado da conexão.

---

## 5. Integração com a Camada Física
Diferente da versão 1, o SNetS2 permite que a ocupação de um recurso afete a qualidade dos outros em tempo real (XT Dinâmico).
*   Sempre que um recurso é alocado, o Plano de Controle pode acionar o modelo físico para validar se o novo circuito causa interferência inaceitável nos circuitos já existentes (`activeQoTForOther`).
*   Caso a interferência ultrapasse o limiar, o Plano de Controle deve reportar uma falha de QoT, gerando um evento de bloqueio.
