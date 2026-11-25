# LEX — Andamento e passagem de contexto

Atualização: 25/11/2026, após validação do incremento 1. Apresentação: 30/11/2026.

## 1. Base verificada

- Branch main sincronizada com origin/main e origin/HEAD segundo o log de push fornecido pelo desenvolvedor.
- Checkpoint 8b8eb2d — test: validate accounting persistence and isolate test database; 14 arquivos, 789 inserções e 4 exclusões.
- Commit anterior 08a584e — feat: adicionar entidades de lancamentos contabeis.
- AGENTS.md e documentação inicial publicados em 9d41a94. O incremento 1 foi aprovado na suíte e aguarda commit.
- Java 21.0.10, Maven Wrapper (Maven 3.10.1 relatado pelo Codex), Spring Boot 4.1.2, Hibernate ORM 7.4.13.Final, PostgreSQL 18.4, conforme relatórios/logs locais. Conferir pom.xml antes de mudanças.
- No incremento 1, 33 fontes principais e sete fontes de teste compiladas. Flyway V1–V5 validado; schema public na versão 5; Hibernate ddl-auto: validate.

## 2. Evidência de regressão

O desenvolvedor executou `.\mvnw.cmd -o clean test` em 25/11/2026, com término às 01:46:32 (UTC−03:00), duração de 32,542 segundos e `BUILD SUCCESS`. Os relatórios Surefire confirmam **50 testes, zero falhas, zero erros e zero ignorados**, incluindo os 25 casos do novo serviço e os 25 já aprovados no checkpoint.

Perfil Spring `test`, PostgreSQL 18.4 em `jdbc:postgresql://127.0.0.1:5432/lex_test`, cinco migrations validadas e schema V5. O desenvolvedor validou a role `lex_test_runner` e a ausência de privilégio `CONNECT` em `lex_dev`.

| Classe | Testes aprovados |
|---|---:|
| LancamentoContabilServiceTest | 25 |
| PlanoContasServiceTest | 8 |
| ContaContabilRepositoryTest | 4 |
| LancamentoContabilRepositoryTest | 6 |
| PartidaContabilRepositoryTest | 5 |
| PlanoContasRepositoryTest | 1 |
| LexApplicationTests | 1 |
| Total | 50 |

Os cenários negativos registraram SQLState 23503 nas chaves estrangeiras `fk_conta_pai` e `fk_conta_reduzida` e SQLState 23505 em `uq_lancamento_estorno_unico`. As classes correspondentes passaram. O novo serviço tem cobertura de autorização, isolamento entre duas empresas, precisão monetária, imutabilidade e rollback. Confirmação, estorno completo e concorrência da numeração seguem pendentes.

Houve falhas de renderização do PSReadLine durante a inserção do bloco de testes, conforme informado pelo desenvolvedor. O resultado final do Maven e os relatórios Surefire registraram execução bem-sucedida.

## 3. Implementação disponível e pontos de inspeção

| Área | Estado |
|---|---|
| Usuário, empresa, auditoria | Estrutura de domínio/persistência existente. O serviço de lançamento valida a titularidade da empresa. Autenticação e utilização efetiva da auditoria nos fluxos seguem pendentes. |
| Plano de contas | Modelo, contas, serviço e repositórios existentes; testes aprovados. |
| Exercício/período | Entidades, repositórios e constraints existentes. |
| Lançamento/partida | Entidades e repositórios, mapeamentos compostos e testes de persistência aprovados. |
| Sequência | Tabela sequencia_lancamento existente na V5; serviço de alocação concorrente pendente. |
| Serviços de lançamento | Incremento 1 concluído e validado: criação manual de rascunho, inclusão e remoção de partidas. |
| API/frontend | Estado completo a inspecionar; integração essencial pendente. |

### Arquivos e contratos do incremento 1

- `LancamentoContabilService.java` implementa as três operações transacionais.
- `PartidaContabil.java` valida conta analítica ativa e valor compatível com `NUMERIC(19,2)`, sem arredondamento.
- `ContaContabilRepository.java` consulta a conta por ID e plano. `PartidaContabilRepository.java` consulta a partida por ID e lançamento.
- `LancamentoContabilServiceTest.java` cobre os 25 casos de integração do incremento.

| Assinatura | Regra |
|---|---|
| `LancamentoContabil criarRascunho(UUID empresaId, UUID usuarioId, UUID exercicioId, UUID periodoId, LocalDate data, String historico)` | Valida usuário ativo, titularidade e empresa ativa e não arquivada, exercício e período vinculados e abertos, data no intervalo, plano da empresa e histórico. Persiste com origem manual, sem número nem dados de confirmação. |
| `PartidaContabil adicionarPartida(UUID empresaId, UUID usuarioId, UUID lancamentoId, UUID contaId, TipoPartida tipo, BigDecimal valor, String historico)` | Valida autorização, bloqueia o lançamento no escopo da empresa, exige estado rascunho e valida conta do plano, tipo, histórico, positividade, escala e precisão do valor. |
| `void removerPartida(UUID empresaId, UUID usuarioId, UUID lancamentoId, UUID partidaId)` | Valida autorização, bloqueia o rascunho no escopo da empresa e exclui somente a partida vinculada ao lançamento. |

O chamador fornece a identidade do usuário autenticado. As mutações usam `buscarParaAlteracao(id, empresaId)` com `PESSIMISTIC_WRITE`. O rascunho pode ficar vazio ou desequilibrado. Após o fechamento do exercício ou período, o serviço ainda permite editar as partidas de um lançamento que permanece em rascunho, em conformidade com a regra de mutação da V5.

## 4. Ambiente de execução

| Perfil | JDBC | Role | Variáveis |
|---|---|---|---|
| Desenvolvimento | jdbc:postgresql://localhost:5432/lex_dev | lex | DB_URL, DB_USERNAME, DB_PASSWORD |
| Testes | jdbc:postgresql://127.0.0.1:5432/lex_test | lex_test_runner | TEST_DB_URL, TEST_DB_USERNAME, TEST_DB_PASSWORD |

application.yaml mantém URL local padrão, exige usuário/senha. application-test.yaml exige as três variáveis TEST_DB_* sem fallbacks. Flyway habilitado e sem datasource independente identificado. 13 tabelas e 3 funções de lex_test transferidas à role de testes, migrations V1–V5 preservadas. CONNECT cruzado bloqueado; conexão real da role de testes ao lex_test passou e ao lex_dev foi negada por ausência de CONNECT. Isso não prova isolamento em relação a todos os outros bancos do servidor. Não executar testes como postgres.

### Executar a suíte no PowerShell

Em uma sessão PowerShell dedicada, execute [`scripts/test.ps1`](../scripts/test.ps1). Na raiz do repositório:

```powershell
.\scripts\test.ps1
.\scripts\test.ps1 -Online
```

O padrão é `.\mvnw.cmd -o clean test`. Use `-Online` quando for necessário obter dependências. De outro diretório, invoque o script pelo caminho completo. A raiz é resolvida a partir do local do script.

O script verifica variáveis e arquivos locais que podem sobrescrever a execução, solicita a senha de `lex_test_runner` no terminal e exige a resposta `lex_test|lex_test_runner|5432|f` do `psql` antes de iniciar o Maven. `PGPASSWORD` é removida logo após a consulta e `TEST_DB_*` é limpo ao final, inclusive em caso de falha. Consulte `$LASTEXITCODE` para o resultado da execução e confira o destino nos logs do Spring e do Flyway.

A validação funcional do script fica para a próxima rodada de testes. Para JUnit no IntelliJ, use `TEST_DB_*` nas configurações locais All in lex e LexApplicationTests. A atualização dessas configurações ainda não foi confirmada. Uma IDE já aberta não herda variáveis de um novo terminal.

## 5. Próximo incremento: confirmação e numeração atômica

1. Após autorização, definir e implementar a confirmação transacional. Validar titularidade, empresa ativa, rascunho, exercício e período abertos, data, contas, mínimo de partidas e equilíbrio.
2. Alocar o número atomicamente por empresa e exercício na mesma transação. Revisar ordem dos locks e flush com a V5 e testar concorrência, isolamento e rollback.
3. Avaliar a precisão dos totais de débito e crédito. As variáveis da V5 usam `NUMERIC(19,2)`, e a soma pode exceder esse limite com partidas individualmente válidas.
4. Tratar o estorno completo em incremento próprio, com inversão de partidas, reserva do original e atualização atômica do original após confirmar o estorno.

## 6. Backlog e riscos

- P0: confirmação, numeração atômica e estorno completo. Na sequência, saldos iniciais, livros e demonstrações, fechamento, estoque PEPS, ativos e depreciação, conforme a meta de recuperação em [decisoes-e-cronograma.md](decisoes-e-cronograma.md).
- P0: entregar link e manual. O enunciado confirma Diário, Razão, Balance (Balancete no planejamento), BP, DRE e os módulos de estoque, ativos e depreciação. Ver [requisitos-academicos.md](requisitos-academicos.md) para orientações de aula, PDFs e a ressalva do modelo de DRE.
- P1: integrar autenticação e utilização efetiva da auditoria, ampliar a cobertura de isolamento para os próximos fluxos e avaliar proteção adicional contra destino errado antes do contexto Spring.
- P1 segurança: fallbacks antigos de credenciais foram removidos, mas valores anteriores podem persistir no histórico Git. Classificar e rotacionar se reais; remoção do arquivo atual não elimina exposição histórica. Não reescrever histórico sem aprovação.
- P2: avisos de repositórios Maven (timeouts/401), agente dinâmico Mockito, templates Thymeleaf ausentes; não bloqueiam checkpoint. Rever quando pertinente, sem ampliar escopo.

## 7. Registro de mudanças

- 22/11: planejamento original; 14 testes aprovados e 8 interfaces de repositório relatadas.
- 25/11: Codex integrado; AGENTS.md validado; teste de estorno alinhado à assinatura de seis argumentos; bancos e roles segregados; YAMLs sem fallback de senha; 25 testes aprovados; commit 8b8eb2d publicado.
- 25/11: documentação de contexto publicada em 9d41a94. Incremento 1 aprovado com 50 testes. Escopo acadêmico obrigatório confirmado e registrada a meta revisada de recuperação.
- Próximo: registrar o incremento validado e a atualização documental em commits separados, após autorização. Iniciar confirmação e numeração em etapa própria.

Fontes: planejamento de 22/11; relatórios do Codex e logs PostgreSQL/Maven/Git de 25/11 fornecidos pelo desenvolvedor. Este documento não substitui inspeção do código vigente.
