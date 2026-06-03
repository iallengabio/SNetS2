# Detalhamento Técnico: Configuração e Mapeamento (Config-as-Code)

Este documento descreve como o SNetS2 interpreta arquivos de configuração JSON e os transforma em um ambiente de simulação ativo.

## 1. O Objeto `experimentSetup`

A configuração é centralizada em um único objeto JSON que segue a estrutura das classes no pacote `com.snets2.config`.

### 1.1. `ConfigLoader` (com.snets2.config.ConfigLoader)
Utiliza a biblioteca **Jackson** para desserialização de alto desempenho.
- Suporta mapeamento de campos aninhados e listas complexas.
- Permite a leitura de arquivos físicos ou strings raw.

### 1.2. `ExperimentalPlanningConfig`
Possui uma lógica dinâmica para suportar *Parameter Sweeps*.
- Utiliza `@JsonAnySetter` para capturar variáveis arbitrárias (ex: `traffic.load`, `simulation.routing`).
- Esta estrutura permite que o simulador realize o produto cartesiano de parâmetros sem código adicional.

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
