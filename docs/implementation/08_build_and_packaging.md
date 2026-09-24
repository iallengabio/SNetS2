# 08. Build e Empacotamento (Build & Packaging)

Este documento descreve a infraestrutura de build, ciclo de vida do Maven e a estratégia de empacotamento em fat JAR executável para o **SNetS2**.

---

## 1. Visão Geral do Ciclo de Vida do Build

O SNetS2 utiliza o **Apache Maven** como ferramenta de gerenciamento de dependências e automação de build, configurado para o **Java 25**.

No Apache Maven, as fases padrão do ciclo de vida incluem:
- `clean`: Remove os artefatos compilados no diretório `target/`.
- `compile`: Compila o código fonte em `src/main/java`.
- `test`: Executa os testes unitários via JUnit 5.
- `package`: Empacota as classes compiladas e recursos em um arquivo JAR executável com dependências embutidas (Fat JAR).
- `install`: Instala o pacote no repositório Maven local (`~/.m2/repository`).

> [!NOTE]
> O Maven não possui uma fase chamada `build`. O comando equivalente para realizar a compilação e geração do executável é `mvn package` (ou `mvn clean package`).

---

## 2. Plugins de Build no `pom.xml`

### 2.1 `maven-compiler-plugin`
- **Versão:** `3.13.0`
- **Release:** `25` (com verificação `-Xlint:all`)
- **Propósito:** Garante compilação estrita compatível com a linguagem Java 25.

### 2.2 `maven-jar-plugin`
- **Versão:** `3.4.1`
- **Manifest:** Define o atributo `Main-Class` apontando para `com.snets2.MainRunner`.

### 2.3 `maven-shade-plugin`
- **Versão:** `3.5.2`
- **Fase:** `package`
- **Propósito:** Gera um Fat/Uber JAR autocontido (`target/SNetS2-1.0-SNAPSHOT.jar`), consolidando todas as dependências em tempo de execução:
  - Jackson Databind & Core (parse de configurações JSON);
  - fastutil (coleções primitivas de alta performance);
  - Apache POI (exportação de relatórios Excel `.xlsx`);
  - FlatLaf & FlatLaf Extras (interface gráfica moderna);
  - SLF4J & Logback (sistema de logging).
- **Transformers configurados:**
  - `ManifestResourceTransformer`: Registra a classe principal executável `com.snets2.MainRunner`.
  - `ServicesResourceTransformer`: Unifica mapeamentos de `META-INF/services` (fundamental para o SPI do Apache POI e bibliotecas de logging).
- **Filtros de Segurança:** Exclusão de assinaturas criptográficas (`META-INF/*.SF`, `META-INF/*.DSA`, `META-INF/*.RSA`) para evitar erros de `SecurityException: Invalid signature file digest`.

---

## 3. Scripts e Comandos de Execução

### 3.1 Script Automatizado de Build (`build.sh`)
O repositório fornece o script auxiliar `build.sh` que resolve o ambiente Maven (inclusive gerenciadores como `mise`) e dispara o processo de compilação:

```bash
./build.sh
```

Para pular a suíte de testes durante compilações rápidas:
```bash
./build.sh -DskipTests
```

### 3.2 Execução do JAR Gerado

Uma vez construído o artefato em `target/SNetS2-1.0-SNAPSHOT.jar`:

- **Modo Gráfico (GUI):**
  ```bash
  java -jar target/SNetS2-1.0-SNAPSHOT.jar
  ```
- **Modo Linha de Comando (CLI para simulações):**
  ```bash
  java -jar target/SNetS2-1.0-SNAPSHOT.jar experiments/experiment01 [num_threads]
  ```
