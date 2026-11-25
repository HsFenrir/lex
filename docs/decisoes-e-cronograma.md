# LEX — Decisões e cronograma de execução

Registro original: 22/11/2026. Consolidação: 25/11/2026.
Janela de desenvolvimento: 23–29/11/2026. Apresentação: 30/11/2026.

## 1. Decisões aprovadas

1. Manter a estratégia e a arquitetura atuais; mudanças somente diante de necessidade técnica ou de prazo demonstrada e com aprovação.
2. Priorizar conclusão e validação do backend antes de acelerar o frontend, sem adiar o frontend para o último dia.
3. Evitar perfeccionismo e expansão indefinida: entregar backend consistente, testado e capaz de sustentar os fluxos essenciais da demonstração.
4. Google Antigravity é ferramenta opcional de aceleração do frontend, somente se o prazo estiver ameaçado. Não substitui arquitetura, revisão ou testes.
5. ChatGPT e/ou Antigravity podem auxiliar o frontend. React + TypeScript é preferência preliminar, não decisão definitiva; inspecionar o repositório antes de escolher.
6. Congelar o escopo: estoque, imobilizado, depreciação e integrações externas não são prioridades, salvo exigência explícita da disciplina.
7. Compilação e inicialização JPA não bastam: exigir testes de persistência, integridade, regras contábeis e fluxos pela API.
8. Iniciar o frontend quando o fluxo básico de lançamentos estiver disponível.
9. Reservar 29/11 para estabilização, correções, testes ponta a ponta, dados de demonstração e ensaio; evitar novas funcionalidades nessa data.
10. Manter commits pequenos e avisar quando uma etapa estiver apta para commit.

## 2. Decisões contábeis e técnicas consolidadas

- Backend Java/Spring Boot, Spring Data JPA, Hibernate, PostgreSQL, Flyway e Maven. Respeitar a estrutura e as versões reais do repositório.
- Preservar isolamento entre empresas, inclusive autorização do usuário sobre a empresa; IDs fornecidos pelo cliente não constituem autorização.
- Preservar as chaves estrangeiras compostas, índices, checks e triggers das migrations V1–V5. Não editar migrations aplicadas sem autorização.
- Lançamento pertence a empresa, exercício, período e plano de contas coerentes. Data dentro do período; exercício e período abertos na confirmação. Empresa ativa.
- Partidas somente em contas analíticas ativas do mesmo plano; valores positivos em BigDecimal com escala máxima de duas casas, sem arredondamento silencioso. Limite NUMERIC(19,2): até 17 dígitos inteiros e duas casas decimais.
- Rascunho pode receber ou perder partidas e não consome número. Confirmação exige ao menos duas partidas, igualdade dos totais de débito e crédito, número positivo e dados de confirmação.
- Numeração por empresa e exercício; alocação atômica dentro da transação de confirmação, protegida contra concorrência. Não usar MAX(numero)+1 sem mecanismo de serialização adequado.
- Lançamentos confirmados e estornados são imutáveis, ressalvada a transição autorizada de CONFIRMADO para ESTORNADO. Exclusão somente de rascunhos.
- Estorno: original confirmado; novo lançamento vinculado, em período aberto, inclusive exercício posterior quando aplicável; copiar partidas invertendo débito e crédito. Confirmar o estorno antes de marcar o original como ESTORNADO, na mesma transação.
- A existência de rascunho de estorno reserva o original, intencionalmente. Preservar índice uq_lancamento_estorno_unico; não admitir segundo estorno. Excluir o rascunho libera a reserva.
- Consultas de partidas sem empresa explícita só são aceitáveis após validação da titularidade/autorização do lançamento pelo chamador. Exigir testes de isolamento.
- Usar bloqueio pessimista do lançamento para mutações concorrentes quando pertinente; revisar ordem de locks e flush contra os triggers da V5.

## 3. Ambiente e processo aprovados em 25/11

- Codex no IntelliJ para inspeção, implementação e testes locais; ChatGPT para arquitetura e revisão; desenvolvedor aprova escopo e operações Git.
- Ler AGENTS.md e os documentos em docs/ antes de implementar. Manter andamento.md atualizado com evidências, não suposições.
- PostgreSQL local 18.4: lex_dev/role lex; lex_test/role lex_test_runner. Conexões cruzadas bloqueadas por CONNECT. postgres permanece administrativo e não deve ser usado pela aplicação ou suíte.
- Configurações por ambiente; senhas nunca em Git, prompts ou logs. Flyway habilitado e Hibernate ddl-auto: validate. Não executar testes contra desenvolvimento.
- Confirmar destino e identidade antes da execução de testes. Não alterar staging, commits, push ou infraestrutura sem autorização.

## 4. Cronograma original (preservado)

| Data | Entrega e atividades |
|---|---|
| 23/11 | Revisar joins compostos de lançamento/partida contra V4/V5, especialmente lançamento–período; testar persistência, recuperação, consultas por empresa/exercício/período e isolamento; clean test e commit após aprovação. |
| 24/11 | Implementar LancamentoContabilService: criar rascunho, incluir/remover partidas, validar propriedade da empresa, plano, contas, exercício e período. |
| 25/11 | Confirmação: equilíbrio débito/crédito, contas válidas, período aberto, número atômico, transação e concorrência. |
| 26/11 | Estorno em rascunho, inversão de partidas, validação do original, confirmação e atualização do original, testes de integração e correções de integridade. |
| 27/11 | Criar/concluir endpoints REST, contratos de entrada/saída, erros de validação; iniciar frontend, navegação e telas principais. |
| 28/11 | Integração frontend/backend; testar acesso, empresa, plano, exercícios, períodos, criação de lançamento, partidas e confirmação. |
| 29/11 | Estabilização, correções, fluxo completo, consistência, inicialização de demonstração, dados de exemplo e ensaio. |
| 30/11 | Apresentação do projeto. |

## 5. Situação em 25/11 e retomada

A etapa de persistência foi validada e publicada em 8b8eb2d; ainda é necessário demonstrar cobertura de isolamento entre empresas nos serviços. A atividade de 24/11 está atrasada. O próximo passo é implementar o serviço de rascunhos e partidas, seguido da confirmação. Não alterar as datas originais sem aprovação. Priorizar recuperação do atraso em incrementos testados, sem suprimir testes nem ampliar o escopo. Ver andamento.md para evidências e lacunas.

## 6. Critérios de sucesso do planejamento original

- Testes automatizados relevantes aprovados.
- Fluxos essenciais de lançamentos executáveis e persistidos corretamente.
- Débitos e créditos validados antes da confirmação.
- Numeração consistente sob concorrência.
- Estorno validado e contabilmente invertido.
- Frontend integrado à API nos fluxos essenciais.
- Ambiente de demonstração inicializável e dados preparados.

Fonte primária: Decisões e Cronograma 23-30 nov 2026.md, registro de 22/11. Complementos: decisões e resultados da transição ao Codex em 25/11. A organização em seções é editorial; não representa aprovação de novas funcionalidades.
