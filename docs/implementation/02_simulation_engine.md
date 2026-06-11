# Detalhamento Técnico: Motor de Simulação (Simulation Engine)

Este documento descreve a implementação do motor de Eventos Discretos (DES) e a mecânica estocástica do SNetS2.

## 1. O Núcleo do Simulador

### 1.1. `SimulationEngine` (com.snets2.engine.SimulationEngine)
Gerencia o ciclo de vida global de uma simulação.
- **Future Event List (FEL):** Implementada via `java.util.PriorityQueue<Event>`. Garante que os eventos sejam processados rigorosamente na ordem temporal.
- **Relógio da Simulação:** A variável `currentTime` avança aos saltos, assumindo o valor do tempo do evento retirado da FEL.
- **Validação de Causalidade:** Se `strictValidationEnabled` estiver ativo, o motor lança uma exceção caso um evento na FEL tente ser processado com um timestamp anterior ao tempo atual da simulação (violação de causalidade).
- **Parâmetros de Carga:** Calcula as taxas $\lambda$ (chegada) e $\mu$ (partida) para atingir a carga em Erlangs desejada.
- **Multi-Bandwidth:** Suporta a geração de requisições com diferentes taxas de bits (`bitRate`) baseada em pesos configuráveis. O método `nextBitRate()` realiza o sorteio estocástico a cada nova chegada.
- **Descarte de Transiente (Warm-up):** Suporta descartar as primeiras $N$ requisições configuradas via `warmUpRequests` para evitar a contaminação das estatísticas de estado estacionário (Steady State).

### 1.2. `Event` (com.snets2.engine.Event)
Classe base abstrata para todas as ações do simulador.
- **Execução:** O método `execute(SimulationEngine)` encapsula a lógica de decisão e mutação.
- **Debug:** Contém a flag estática `debugEnabled` para controle global de logs de depuração.

---

## 2. Tipologia e Ciclo de Vida dos Eventos

Os eventos de chegada, estabelecimento, bloqueio e observação integram verificações junto ao método `SimulationEngine.isWarmUp()` para garantir o descarte dos dados de métricas acumulados no período transiente inicial.

### 2.1. `ArrivalEvent`
Marca a entrada de uma nova solicitação.
1.  Agenda a próxima chegada (Processo de Poisson).
2.  Consulta o `ControlPlane` para realizar a alocação de recursos (RMSCA).
3.  Agenda um `SetupEvent` (Sucesso) ou `BlockEvent` (Falha).

### 2.2. `SetupEvent`
Efetiva a conexão na rede.
1.  Chama `ControlPlane.establishCircuit`.
2.  Gera um tempo de retenção (Exponencial) e agenda o `DepartureEvent`.

### 2.3. `DepartureEvent` & `TeardownEvent`
Gerenciam o fim da vida de uma conexão.
- O `DepartureEvent` sinaliza que o tempo expirou.
- O `TeardownEvent` realiza a limpeza no `ControlPlane`, liberando slots e portas de hardware.

### 2.4. `BlockEvent`
Evento puramente administrativo para registro de estatísticas de rejeição de chamadas.

---

## 3. Utilidades Estocásticas

### 3.1. `RandomGenerator` (com.snets2.util.RandomGenerator)
Centraliza a geração de números aleatórios.
- **Distribuições:** Implementa a Distribuição Exponencial através do método de transformação inversa: $T = -\frac{\ln(1-U)}{\lambda}$.
- **Reprodutibilidade:** Garante que múltiplas execuções com a mesma semente (*seed*) produzam exatamente os mesmos resultados.
