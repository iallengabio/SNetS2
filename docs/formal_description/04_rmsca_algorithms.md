# SNetS2: Algoritmos de RMSCA (Routing, Modulation, Spectrum, and Core Allocation)

## 1. Visão Geral
A modularidade é um dos pilares do SNetS2. Para permitir que pesquisadores testem novas heurísticas sem modificar o núcleo do simulador, o processo de alocação de recursos é decomposto em quatro sub-problemas principais, coletivamente chamados de **RMSCA**.

O Plano de Controle utiliza um **Padrão de Fábrica (Factory Pattern)** e **Reflexão** para instanciar os algoritmos definidos no arquivo de configuração JSON (`simulation` block).

---

## 2. Arquitetura Modular
Os algoritmos de RMSCA podem ser implementados de duas formas:
1.  **Abordagem Desacoplada:** Quatro algoritmos independentes que são chamados em sequência pelo Plano de Controle.
2.  **Abordagem Integrada:** Um único algoritmo que resolve todos os sub-problemas simultaneamente (muito comum para técnicas de Cross-Layer Optimization).

### 2.1. Interfaces de Algoritmos (Contratos)
Para garantir a interoperabilidade, cada tipo de algoritmo deve implementar uma interface específica:

*   **`IRouting`**: Recebe origem/destino e retorna uma lista de caminhos candidatos (`Path`).
*   **`ICoreAssignment`**: Recebe um caminho e retorna o índice do núcleo (`Core`) a ser utilizado.
*   **`IModulationSelection`**: Recebe um caminho e requisitos de banda, retornando o formato de modulação e o número de slots necessários.
*   **`ISpectrumAssignment`**: Recebe o caminho, o núcleo e a quantidade de slots, retornando os índices de início/fim dos slots (`SpectrumInterval`).
*   **`IIntegratedRMSCA`**: Interface única que recebe a requisição completa e retorna um objeto `AllocationSolution` (ou nulo em caso de bloqueio).

---

## 3. Fluxo de Decisão do RMSCA

O fluxo padrão executado pelo Plano de Controle ao receber uma `ArrivalEvent` é:

1.  **Cálculo de Caminhos:** O algoritmo de *Routing* gera $k$ caminhos.
2.  **Loop de Tentativas:** Para cada caminho candidato:
    a. **Seleção de Modulação:** Determina a modulação viável para a distância do caminho.
    b. **Atribuição de Core:** Escolhe o núcleo alvo.
    c. **Alocação Espectral:** Tenta encontrar slots contíguos no núcleo/caminho escolhido.
    d. **Validação de QoT:** O Plano de Controle verifica se a solução atende aos requisitos físicos (ASE, NLI, XT).
3.  **Resultado:** Se uma combinação válida for encontrada, o circuito é agendado. Caso contrário, a requisição é bloqueada.

---

## 4. Exemplos de Heurísticas Clássicas
O SNetS2 virá com uma biblioteca de algoritmos base prontos para uso:

*   **Routing:** Dijkstra (Shortest Path), k-Shortest Paths (KSP).
*   **Spectrum Assignment:** First Fit (FF), Random Fit (RF), Last Fit (LF), Exact Fit (EF).
*   **Core Assignment:** First Fit Core, Random Fit Core, Min-Crosstalk Core Assignment.
*   **Modulation:** Fixed Modulation, Distance-Adaptive Modulation.

---

## 5. Implementação de Novos Algoritmos
Para adicionar um novo algoritmo ao SNetS2:
1.  Criar uma nova classe que implemente uma das interfaces RMSCA.
2.  Registrar a classe no sistema de mapeamento do simulador.
3.  Referenciar o nome da classe no campo correspondente do JSON de entrada.

Esta estrutura permite que o simulador evolua junto com o estado da arte das redes ópticas elásticas multicore.
