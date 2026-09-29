# Revisão técnica do SNetS2

Revisão feita sobre o commit `e64a13d`, com o relatório de V&V executado após o PR #11. Quatro documentos:

1. **[Revisão da documentação](01_revisao_documentacao.md)**: confere a precisão técnica e a aderência à literatura de `README`, `docs/formal_description` e `docs/implementation`, com uma correção proposta para cada item.
2. **[Code review](02_code_review.md)**: compara a implementação com o modelo previsto. São 21 achados, classificados por severidade, e os principais foram confirmados por micro-experimentos.
3. **[Plano de verificação](03_plano_de_verificacao.md)**: 13 níveis de experimentos, dos invariantes e do Erlang-B até a camada física SDM e a comparação com a literatura. Cada experimento tem oráculo, critério estatístico e dependência das correções.
4. **[Relatório de verificação e validação](04_relatorio_verificacao_validacao.md)**: campanha de 8 experimentos (Erlang B, transmissores, Kaufman–Roberts, cadeia de Markov exata, algoritmos RMSA na NSFNET, energia, camada física e rede SDM com QoT), com gráficos, tabelas e vereditos. Reprodução: `scripts/verification/run_campaign.sh`.

Teste executável que acompanha a revisão: `src/test/java/com/snets2/verification/ErlangBSingleLinkTest.java`.

## Achados críticos
| ID | Resumo |
| :-- | :-- |
| CR-01 | NLI cerca de 10¹⁰ vezes menor que o esperado por um erro de unidade. Na prática, `activeNLI` não tem efeito. |
| CR-02 | As métricas ponderadas no tempo são amostradas depois da mutação de estado, o que gera viés (utilização medida de 0,426 contra 0,333 teórico). |
| CR-03 | `modulationSelection` é ignorado. O `experiment01` declara "fixed", mas executa modulação adaptativa. |
