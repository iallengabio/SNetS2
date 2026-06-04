# Documentação da Implementação do SNetS2

Este diretório contém o detalhamento técnico minucioso de cada componente do SNetS2, associando o código fonte aos seus conceitos teóricos e físicos.

## Princípios de Desenvolvimento

1.  **Regra Indispensável de Sincronização:** Toda nova funcionalidade, classe de modelo ou algoritmo deve ser refletido nestes documentos imediatamente após a implementação.
2.  **Camada de Validação Estrita (Guardrails):** O simulador implementa verificações de integridade em tempo real para detectar operações ilegais (ex: eventos que voltam no tempo, dupla alocação de recursos).
    - **Objetivo:** Garantir a correção científica dos resultados e facilitar a depuração de novos algoritmos RMSCA.
    - **Performance:** Esta camada pode ser desativada via `SimulationConstants.strictValidationEnabled` para maximizar a velocidade em execuções de produção.
3.  **Consciência Energética:** O simulador é projetado para rastrear o consumo de potência de cada componente físico (nós, amplificadores, transceptores), permitindo análises de eficiência energética da rede como uma métrica de primeira classe.

## Seções

1.  **[Network Model](01_network_model.md)**: Detalhes sobre Espectro, Cores, Links, Amplificadores e Nós.
2.  **[Simulation Engine](02_simulation_engine.md)**: Detalhes sobre o motor DES, FEL e ciclo de vida de eventos.
3.  **[Config-as-Code](03_config_as_code.md)**: Detalhes sobre o parser JSON, mapeamento de topologia e fábrica de algoritmos.
4.  **[RMSCA Logic](04_rmsca_logic.md)**: Detalhes sobre as interfaces e implementações de Roteamento, Modulação e Alocação de Espectro/Core.
5.  **[Metrics System](05_metrics_system.md)**: Detalhes sobre a coleta de dados, eventos de observação ponderados pelo tempo e formatação de saída.
6.  **[Physical Layer Architecture](06_physical_layer_architecture.md)**: Estratégia de otimização via Incremental State Caching para OSNR e Crosstalk.
7.  **[Multithreading and Checkpointing](07_multithreading_and_checkpointing.md)**: Detalhes sobre execução concorrente de replicações e persistência de progresso incremental.

---
*Este conjunto de documentos visa viabilizar um desenvolvimento agentico saudável e garantir a manutenibilidade a longo prazo do simulador.*
