# Detalhamento Técnico: Configuração e Mapeamento (Config-as-Code)

Este documento descreve como o SNetS2 interpreta arquivos de configuração JSON e os transforma em um ambiente de simulação ativo.

## 1. A Hierarquia de Configuração

O SNetS2 utiliza uma abordagem de duas camadas para gerenciar configurações, separando a definição do experimento da execução concreta de cada simulação.

### 1.1. `ExperimentSetup` (com.snets2.config.ExperimentSetup)
Representa o arquivo JSON bruto. Ele contém:
- As configurações base da rede, camada física, simulação e tráfego.
- O bloco `experimentalPlanning`, que define as variáveis de varredura (*Parameter Sweep*) e o número de replicações.

### 1.2. `ScenarioSetup` (com.snets2.config.ScenarioSetup)
Representa uma configuração concreta e pronta para execução para um único cenário. Ele é derivado do `ExperimentSetup` e contém apenas as chaves de sistema (`networkTopology`, `physicalLayer`, `simulation`, `traffic`), com todos os valores de varredura já aplicados.

---

## 2. Parameter Sweep Genérico

Diferente de abordagens tradicionais com mapeamentos manuais, o SNetS2 utiliza manipulação dinâmica de árvore JSON para permitir a varredura de **qualquer parâmetro**.

### 2.1. `ConfigLoader.applyOverrides`
Este método é o coração do planejamento experimental:
1. Converte o `ScenarioSetup` base em um mapa genérico (`Map<String, Object>`).
2. Navega pela estrutura usando a notação de ponto (ex: `physicalLayer.guardBand`).
3. Substitui o valor folha pelo valor definido no cenário atual.
4. Reconverte o mapa resultante em um novo objeto `ScenarioSetup` tipado.

Essa arquitetura garante que, ao adicionar qualquer novo campo ao JSON de configuração, ele se torne automaticamente "variável" no planejamento experimental, sem necessidade de alterar o código Java do `ExperimentalPlanner`.


---

## 2. Mapeamento para o Modelo Real

### 2.1. `TopologyMapper` (com.snets2.config.TopologyMapper)
Realiza a ponte entre os POJOs do Jackson e os objetos de simulação.
- **Nós:** Instancia objetos `Node` com suas capacidades de Tx/Rx.
- **Enlaces e Cores:** Para cada link, instancia a geometria de núcleos definida no bloco `cores` do JSON.
- **Modulações:** Converte a lista técnica de modulações para instâncias de `ModulationFormat`.

### 2.2. `AlgorithmFactory` (com.snets2.rmsca.AlgorithmFactory)
Provê modularidade total via injeção de dependência baseada em Strings.
- **Reflection:** Instancia as classes de algoritmos (como `StandardIntegratedRMSCA`) baseadas no nome fornecido no JSON.
- **Registro:** Permite que novos algoritmos sejam adicionados ao simulador sem alterar o núcleo do `MainRunner`.
