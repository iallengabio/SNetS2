# SNetS2: Módulo de Eventos e Motor de Simulação (DES Engine)

## 1. Visão Geral
O SNetS2 opera sob o paradigma de **Simulação de Eventos Discretos (DES)**. O tempo não avança de forma contínua, mas sim em "pulos", saltando diretamente para o instante de tempo do próximo evento agendado.

O Módulo de Eventos é o "coração" dinâmico do simulador, sendo composto pela Máquina de Simulação e pela Lista de Eventos Futuros (FEL).

---

## 2. Componentes Principais

### 2.1. Futura Event List (FEL)
Uma fila de prioridade (Priority Queue) que armazena todos os eventos agendados, ordenados crescentemente pelo tempo de ocorrência ($t$).
*   O motor de simulação sempre retira o evento com o menor $t$ para processamento.
*   Novos eventos podem ser inseridos na FEL a qualquer momento durante a execução.

### 2.2. O Motor de Simulação (Simulation Loop)
O ciclo de vida de uma simulação (uma única replicação) segue este fluxo:
1.  **Inicialização:** O tempo é zerado ($t=0$). A FEL é populada com o primeiro evento de chegada (`Arrival`). O Módulo de Sistema é resetado.
2.  **Loop Principal:** Enquanto a condição de parada não for atingida (ex: atingir o número total de `requests` configurado):
    *   Retira o evento mais próximo da FEL.
    *   Atualiza o relógio da simulação para o tempo do evento.
    *   Processa o evento (o que pode alterar o estado da rede no Módulo de Sistema ou gerar novos eventos).
3.  **Finalização:** Coleta métricas finais e limpa a memória para a próxima replicação.

---

## 3. Tipologia de Eventos

No SNetS2, os eventos são objetos que encapsulam a lógica central de decisão e mutação de estado. **A regra de ouro da arquitetura é: o Módulo de Sistema nunca altera seu próprio estado.** O Plano de Controle apenas responde a consultas e realiza cálculos (como executar o RMSCA). Quem julga as respostas e efetiva as mudanças são exclusivamente os Eventos.

Os principais tipos de eventos são:

### 3.1. Chegada de Requisição (`ArrivalEvent`)
Representa a solicitação de uma nova conexão entre dois nós.
*   **Consulta:** O evento invoca o Plano de Controle solicitando a execução dos algoritmos RMSCA para encontrar uma solução de alocação viável. O Plano de Controle retorna uma proposta (solução) ou informa que não há recursos.
*   **Decisão (Sucesso):** Se a solução for válida, o `ArrivalEvent` gera e agenda um `SetupEvent` para o instante de tempo atual (imediato), passando a solução como parâmetro. Também agenda a próxima `ArrivalEvent` com base na taxa de chegada ($\lambda$).
*   **Decisão (Falha):** Se não houver solução, o `ArrivalEvent` gera e agenda um `BlockEvent` (imediato) para registro. A próxima `ArrivalEvent` continua sendo agendada.

### 3.2. Estabelecimento de Circuito (`SetupEvent`)
É o evento responsável por efetivar a alocação de recursos na rede.
*   **Mutação de Estado:** O evento chama métodos do Módulo de Sistema informando quais recursos devem ser marcados como ocupados (Slots, Tx/Rx, Regeneradores) e cria o registro do `Circuit` ativo.
*   **Ação Futura:** O `SetupEvent` gera e agenda um `DepartureEvent` para o instante $t + \text{hold\_time}$.

### 3.3. Partida de Conexão (`DepartureEvent`)
Indica que o tempo de retenção de um circuito expirou.
*   **Decisão:** O evento simplesmente identifica o circuito que deve ser encerrado e gera/agenda um `TeardownEvent` imediato.

### 3.4. Desestabelecimento de Circuito (`TeardownEvent`)
É o evento responsável por liberar os recursos na rede.
*   **Mutação de Estado:** O evento chama métodos do Módulo de Sistema informando que o `Circuit` foi encerrado, liberando os slots e devolvendo os recursos de hardware (Tx/Rx/Regeneradores) para os nós.

### 3.5. Evento de Bloqueio (`BlockEvent`)
Evento administrativo para registro de métricas.
*   **Mutação de Estado:** Não altera a topologia, mas atualiza o estado dos contadores de métricas no sistema, registrando a causa raiz do bloqueio (Falta de espectro, QoT, etc).

### 3.6. Eventos de Observação (`ObserverEvent` / `SnapshotEvent`)
Eventos especiais dedicados puramente à coleta de métricas baseadas em tempo (Time-Average Statistics).
*   **Comportamento Periódico:** Diferente do `Arrival` que é estocástico, os eventos de observação são agendados com um intervalo constante ($\Delta t_{obs}$). Assim que um `ObserverEvent` é processado, ele coleta os dados e agenda o próximo para $t + \Delta t_{obs}$.
*   **Coleta de Dados:** O evento consulta o Módulo de Sistema e tira um "retrato" (snapshot) de métricas instantâneas, como:
    *   Utilização atual do espectro (quantidade de slots ocupados / total).
    *   Taxa de fragmentação espectral naquele milissegundo.
    *   Uso de hardware (quantos Tx/Rx estão em uso).
*   **Contraste com Métricas Reativas:** Enquanto o `BlockEvent` ou `SetupEvent` incrementam métricas *reativas* (baseadas em ocorrência), o `ObserverEvent` garante que o simulador avalie a utilização média ao longo da linha do tempo.

---

## 4. Fluxo Estrito de Eventos e Sistema

A filosofia de separação de responsabilidades garante que todo e qualquer *log* ou *debug* possa ser feito puramente acompanhando a pilha da FEL.

```text
[FEL] --> Retira Evento: ArrivalEvent (t)
             |
             +-- Consulta --> [Módulo de Sistema: Roda RMSCA] -- (Retorna Solução)
             |
             +-- Se Solução OK --> Agenda SetupEvent (t)
             |                 --> Agenda próximo ArrivalEvent (t + delta)
             |
             +-- Se Sem Solução -> Agenda BlockEvent (t)
                               --> Agenda próximo ArrivalEvent (t + delta)


[FEL] --> Retira Evento: SetupEvent (t)
             |
             +-- Comando Mutação --> [Módulo de Sistema: Marca recursos ocupados, Salva Circuito]
             |
             +-- Ação Futura   --> Agenda DepartureEvent (t + hold_time)


[FEL] --> Retira Evento: DepartureEvent (t + hold_time)
             |
             +-- Ação Futura   --> Agenda TeardownEvent (t + hold_time)


[FEL] --> Retira Evento: TeardownEvent (t + hold_time)
             |
             +-- Comando Mutação --> [Módulo de Sistema: Libera recursos e destrói Circuito]
```

---

## 5. Condições de Parada e Estabilização
*   **Parada:** A simulação termina quando o contador de eventos de `Arrival` atinge o valor definido em `simulation.requests`.
*   **Warm-up (Transitório):** Os primeiros eventos podem ser descartados das métricas para garantir que os dados reflitam o "estado estacionário" (Steady State) da rede.
