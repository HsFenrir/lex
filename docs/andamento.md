# LEX — Andamento e passagem de contexto

Atualização em 25/11/2026, após a conclusão antecipada do incremento 4A. Apresentação em 30/11/2026.

## 1. Base verificada

- Branch main sincronizada com origin/main e origin/HEAD segundo o log de push fornecido pelo desenvolvedor.
- Checkpoint `edf11d1`, com o incremento 3 de estorno e cancelamento. O incremento 4A foi concluído em 25/11 como antecipação parcial das atividades previstas para 26/11 e está apto para commit.
- AGENTS.md e documentação inicial publicados em 9d41a94. Incremento 1 publicado em 54a4b39, script de testes em 9fbd7d1, escopo acadêmico confirmado em 1bf3fbc e incremento 2 publicado em 47cf2a8.
- Java 21.0.10, Maven Wrapper (Maven 3.10.1 relatado pelo Codex), Spring Boot 4.1.2, Hibernate ORM 7.4.13.Final, PostgreSQL 18.4, conforme relatórios/logs locais. Conferir pom.xml antes de mudanças.
- No incremento 4A, foram compiladas 36 fontes principais e dez fontes de teste. Flyway V1–V6 validado, schema `public` na V6 e Hibernate em `ddl-auto: validate`.

## 2. Evidência de regressão

O desenvolvedor executou `.\scripts\test.ps1` em 25/11/2026, com término às 23:24:49 (UTC−03:00), duração de 48,323 segundos e `BUILD SUCCESS`. Os dez relatórios Surefire confirmam **92 testes, zero falhas, zero erros e zero ignorados**, incluindo os cinco casos do Livro Diário e os 87 testes anteriores. As classes dos quatro arquivos do incremento foram compiladas após a última alteração nas fontes e antes da geração dos relatórios. Os cinco métodos de teste do Diário coincidem com os casos registrados no Surefire.

Perfil Spring `test`, PostgreSQL 18.4 em `jdbc:postgresql://127.0.0.1:5432/lex_test`. Foram validadas seis migrations, com o schema `public` na V6 e aplicação integral de V1 a V6 em nove schemas descartáveis: três para confirmação e seis para estorno. Os testes aguardam o encerramento das threads com timeout e programam a remoção de cada schema gerado em `finally`. O script exige a role `lex_test_runner` e a ausência de privilégio `CONNECT` em `lex_dev` antes de iniciar o Maven.

| Classe | Testes aprovados |
|---|---:|
| EstornoLancamentoContabilServiceTest | 19 |
| ConfirmacaoLancamentoContabilServiceTest | 18 |
| LancamentoContabilServiceTest | 25 |
| LivroDiarioServiceTest | 5 |
| PlanoContasServiceTest | 8 |
| ContaContabilRepositoryTest | 4 |
| LancamentoContabilRepositoryTest | 6 |
| PartidaContabilRepositoryTest | 5 |
| PlanoContasRepositoryTest | 1 |
| LexApplicationTests | 1 |
| Total | 92 |

Os cenários negativos incluem violações esperadas das chaves estrangeiras `fk_conta_pai` e `fk_conta_reduzida` e das regras de unicidade `uq_lancamento_estorno_unico` e `uq_lancamento_numero_empresa_exercicio`. Os testes respectivos passaram. A colisão de número foi provocada após a alocação para verificar que o lançamento permanece em rascunho e a atualização do contador é revertida.

O teste de rollback do estorno provocou uma falha na transição do original após o `flush` da confirmação do estorno, por meio da constraint `teste_bloquear_estornado` em schema descartável. A verificação em outra transação confirmou que o original permaneceu `CONFIRMADO`, o estorno voltou a `RASCUNHO` sem número, a reserva foi mantida e a sequência foi revertida.

Na validação do incremento 1, em 25/11 às 01:46:32 (UTC−03:00), `.\mvnw.cmd -o clean test` concluiu com 50 testes aprovados. As falhas de renderização do PSReadLine na inserção manual do bloco não afetaram esse resultado.

## 3. Implementação disponível e pontos de inspeção

| Área | Estado |
|---|---|
| Usuário, empresa, auditoria | Estrutura de domínio/persistência existente. O serviço de lançamento valida a titularidade da empresa. Autenticação e utilização efetiva da auditoria nos fluxos seguem pendentes. |
| Plano de contas | Modelo, contas, serviço e repositórios existentes; testes aprovados. |
| Exercício/período | Entidades, repositórios e constraints existentes. |
| Lançamento/partida | Entidades e repositórios, mapeamentos compostos e testes de persistência aprovados. |
| Sequência | Alocação atômica validada, com contador independente por empresa e exercício. |
| Serviços de lançamento | Incrementos 1 a 3 validados. Criação de rascunho, inclusão e remoção de partidas manuais, confirmação com numeração atômica e estorno completo disponíveis. |
| Livro Diário | Incremento 4A validado. Serviço de emissão e consulta compartilhada de partidas escrituradas disponíveis. |
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

### Contratos do incremento 2

- `LancamentoContabil confirmarLancamento(UUID empresaId, UUID usuarioId, UUID lancamentoId)` confirma um rascunho autorizado e registra número, responsável e horário. Exige usuário ativo, empresa ativa e não arquivada, exercício e período abertos, data dentro do período e ao menos duas partidas válidas e equilibradas em contas analíticas ativas do plano. A confirmação genérica rejeita rascunhos de estorno.
- `Optional<Long> alocarNumero(UUID empresaId, UUID exercicioId)`, em `SequenciaLancamentoRepository`, exige transação ativa. Usa `INSERT … ON CONFLICT … DO UPDATE … RETURNING` para iniciar em 1 ou incrementar atomicamente `ultimo_numero`. Ao atingir `Long.MAX_VALUE`, o SQL retorna zero linhas e o repositório devolve `Optional.empty()`. O serviço lança `IllegalStateException` antes de alterar o lançamento. Erros de persistência são propagados.

Os bloqueios são adquiridos na ordem **lançamento → período → exercício → empresa → usuário → contas ordenadas por ID → sequência**. O bloqueio pessimista do lançamento serializa confirmações e mutações de partidas do mesmo rascunho. Os bloqueios compartilhados protegem os estados consultados até o fim da transação. A linha da sequência serializa alocações para a mesma empresa e exercício. O contador e a confirmação são gravados na mesma transação, com `flush` antes do retorno. Os testes em transações independentes comprovaram uma única confirmação para o mesmo lançamento, números distintos para lançamentos do mesmo exercício e rollback do contador em caso de rejeição pelo banco.

A V6 amplia apenas os acumuladores de débito e crédito do trigger para `NUMERIC` sem precisão fixa. As partidas permanecem em `NUMERIC(19,2)`. Um teste confirma um lançamento equilibrado cujos totais agregados superam o limite de uma partida individual.

### Contratos do incremento 3

| Assinatura | Regra |
|---|---|
| `LancamentoContabil criarRascunhoEstorno(UUID empresaId, UUID usuarioId, UUID lancamentoOriginalId, UUID exercicioDestinoId, UUID periodoDestinoId, LocalDate data, String historico)` | Valida autorização, original confirmado e livre de reserva, destino aberto e data igual ou posterior à do original. Copia cada partida com a mesma conta, valor e histórico, invertendo débito e crédito. O rascunho fica sem número e reserva o original, que permanece confirmado. |
| `LancamentoContabil confirmarEstorno(UUID empresaId, UUID usuarioId, UUID estornoId)` | Revalida o vínculo e a equivalência por conta, tipo, valor e multiplicidade. Aloca o número no exercício de destino e grava a confirmação antes de marcar o original como `ESTORNADO`. Preserva os demais dados e partidas do original. |
| `void cancelarRascunhoEstorno(UUID empresaId, UUID usuarioId, UUID estornoId)` | Exclui as partidas do estorno em rascunho e depois o cabeçalho, liberando a reserva. O original permanece confirmado. |

As operações genéricas de adição e remoção de partidas e de confirmação rejeitam estornos em rascunho. A criação aceita origens `MANUAL` e `ABERTURA`. Um estorno não pode ser estornado. Contas inativas impedem a criação e a confirmação, mesmo quando estavam ativas na contabilização original. O cancelamento continua disponível para liberar a reserva.

O bloqueio do original precede o do estorno existente. Os demais recursos seguem a ordem definida no incremento 2. O índice único protege a reserva, e a confirmação do estorno, a alocação do número e a transição do original ocorrem em uma única transação. Os testes com transações independentes cobrem criação e confirmação concorrentes, confirmação contra cancelamento, cancelamento contra nova criação e estorno concorrente com confirmação manual no mesmo exercício.

As reversões de lançamentos de origem operacional dependem de integração futura com os respectivos módulos, incluindo estoque, ativos, depreciação e fechamento. O registro efetivo do fluxo na auditoria também permanece pendente.

### Contrato do incremento 4A

`LivroDiarioService.emitir(UUID empresaId, UUID usuarioId, UUID exercicioId, LocalDate inicio, LocalDate fim)` retorna um `LivroDiario` para um intervalo inclusivo contido no exercício. A emissão exige usuário ativo e titular de uma empresa ativa e não arquivada. O serviço valida o exercício no escopo da empresa. As chaves estrangeiras compostas garantem o vínculo dos períodos dos lançamentos com o exercício e a empresa.

`PartidaContabilRepository.buscarMovimentosEscriturados(UUID empresaId, UUID exercicioId, LocalDate inicio, LocalDate fim)` é a consulta compartilhada. Retorna uma projeção escalar `MovimentoEscriturado` por partida em um único SQL, com os dados do lançamento, da conta e a referência do original quando houver estorno. Filtra por empresa, exercício e data contábil, com ordenação por data, número, UUID do lançamento e UUID da partida. Inclui lançamentos `CONFIRMADOS` e originais `ESTORNADOS`, preservando original e estorno em suas respectivas datas. Exclui `RASCUNHOS` e permite consultar exercícios e períodos encerrados e contas posteriormente inativadas. A descrição da conta vem do cadastro atual no momento da consulta.

O serviço agrupa as linhas em DTOs imutáveis, com listas copiadas e sem expor entidades JPA. O retorno identifica empresa, exercício e intervalo, preserva lançamentos e partidas individuais e inclui referências, históricos, origem e status. Os totais de débito e crédito por lançamento e por intervalo são calculados em `BigDecimal` a partir das mesmas linhas retornadas. A consulta única de movimentos evita N+1.

A emissão abre uma transação de leitura com `REPEATABLE_READ` quando executada sem transação externa, mantendo autorização, exercício e movimentos no mesmo snapshot. Se a chamada participar de uma transação externa com isolamento inferior, prevalece o isolamento externo e as validações e a consulta podem usar snapshots diferentes. Cabeçalhos, partidas e referências ainda compartilham o snapshot da instrução SQL de movimentos.

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

O modo padrão do script foi validado nas suítes de 68, 87 e 92 testes. Para JUnit no IntelliJ, use `TEST_DB_*` nas configurações locais All in lex e LexApplicationTests. A atualização dessas configurações ainda não foi confirmada. Uma IDE já aberta não herda variáveis de um novo terminal.

## 5. Próxima etapa — Livro Razão e apuração compartilhada de saldos

Com o Livro Diário concluído no incremento 4A, a próxima etapa é implementar o Livro Razão e a apuração compartilhada de saldos a partir dos movimentos efetivamente escriturados.

**Regra da escrituração efetiva.** Lançamentos `CONFIRMADOS`, inclusive os estornos confirmados, e lançamentos originais `ESTORNADOS` integram a escrituração. Cada um produz efeitos na sua própria data contábil, respeitado o corte do relatório. Lançamentos em `RASCUNHO` ficam fora dos saldos. O original e seu estorno podem produzir efeitos em períodos diferentes.

## 6. Backlog e riscos

- P0: saldos iniciais, escrituração, demonstrações e fechamento automatizado. Na sequência, estoque PEPS, ativos e depreciação, conforme a meta de recuperação em [decisoes-e-cronograma.md](decisoes-e-cronograma.md).
- P0: entregar link e manual. O enunciado confirma Diário, Razão, Balance (Balancete no planejamento), BP, DRE e os módulos de estoque, ativos e depreciação. Ver [requisitos-academicos.md](requisitos-academicos.md) para orientações de aula, PDFs e a ressalva do modelo de DRE.
- O projeto adotará um modelo próprio de DRE, conforme a decisão já registrada. A implementação pode avançar enquanto permanece a ressalva acadêmica sobre a disponibilidade do modelo do professor e eventuais diferenças de formato. [Requisitos acadêmicos](requisitos-academicos.md#4-enunciado-orientações-de-aula-e-decisões-complementares).
- P1: integrar autenticação e utilização efetiva da auditoria, conectar o estorno às reversões operacionais e ampliar a cobertura de isolamento para os próximos fluxos. Avaliar proteção adicional contra destino errado antes do contexto Spring.
- P1: um contador legado defasado pode colidir com número já utilizado. A restrição de unicidade rejeita a confirmação e a transação reverte a alocação. A reconciliação de dados legados permanece pendente caso a divergência seja encontrada.
- P1 segurança: fallbacks antigos de credenciais foram removidos, mas valores anteriores podem persistir no histórico Git. Classificar e rotacionar se reais; remoção do arquivo atual não elimina exposição histórica. Não reescrever histórico sem aprovação.
- P2: avisos de repositórios Maven (timeouts/401), agente dinâmico Mockito, templates Thymeleaf ausentes; não bloqueiam checkpoint. Rever quando pertinente, sem ampliar escopo.

## 7. Registro de mudanças

- 22/11: planejamento original; 14 testes aprovados e 8 interfaces de repositório relatadas.
- 25/11: Codex integrado; AGENTS.md validado; teste de estorno alinhado à assinatura de seis argumentos; bancos e roles segregados; YAMLs sem fallback de senha; 25 testes aprovados; commit 8b8eb2d publicado.
- 25/11: documentação de contexto publicada em 9d41a94. Incremento 1 aprovado com 50 testes. Escopo acadêmico obrigatório confirmado e registrada a meta revisada de recuperação.
- 25/11: incremento 2 aprovado com 68 testes após a correção do tratamento de esgotamento. V6 e modo padrão do script de testes validados.
- 25/11: incremento 3 aprovado com 87 testes. Núcleo de lançamentos validado, incluindo estorno e cancelamento.
- 25/11: incremento 4A concluído antecipadamente, com Livro Diário e consulta compartilhada de movimentos escriturados. Suíte de 92 testes aprovada.

Fontes: planejamento de 22/11; relatórios do Codex e logs PostgreSQL/Maven/Git de 25/11 fornecidos pelo desenvolvedor. Este documento não substitui inspeção do código vigente.
