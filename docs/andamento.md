# LEX — Andamento e passagem de contexto

Atualização em 26/11/2026, após a conclusão do incremento 4C. Apresentação em 30/11/2026.

## 1. Base verificada

- Branch main sincronizada com origin/main e origin/HEAD segundo o log de push fornecido pelo desenvolvedor.
- Checkpoint `be1ad09`, com o incremento 4B publicado. O incremento 4C está apto para commit.
- As metas de 25/11 foram integralmente concluídas. Os serviços de Livro Diário e Livro Razão foram antecipados em relação às atividades previstas para 26/11.
- AGENTS.md e documentação inicial publicados em 9d41a94. Incremento 1 publicado em 54a4b39, script de testes em 9fbd7d1, escopo acadêmico confirmado em 1bf3fbc e incremento 2 publicado em 47cf2a8.
- Java 21.0.10, Maven Wrapper (Maven 3.10.1 relatado pelo Codex), Spring Boot 4.1.2, Hibernate ORM 7.4.13.Final, PostgreSQL 18.4, conforme relatórios/logs locais. Conferir pom.xml antes de mudanças.
- No incremento 4C, foram compiladas 42 fontes principais e 12 fontes de teste. Flyway V1–V6 validado, schema `public` na V6 e Hibernate em `ddl-auto: validate`.

## 2. Evidência de regressão

O desenvolvedor executou `.\scripts\test.ps1` em 26/11/2026, com término às 19:45:05 (UTC−03:00), duração de 51,818 segundos e `BUILD SUCCESS`. Os 12 relatórios Surefire confirmam **105 testes, zero falhas, zero erros e zero ignorados**, incluindo oito casos de abertura e os 97 testes anteriores. As classes dos seis arquivos do incremento foram compiladas após as últimas alterações nas fontes e antes da geração dos relatórios. Os oito métodos de `AberturaContabilServiceTest` coincidem com os casos registrados no Surefire.

Perfil Spring `test`, PostgreSQL 18.4 em `jdbc:postgresql://127.0.0.1:5432/lex_test`. Foram validadas seis migrations, com o schema `public` na V6 e aplicação integral de V1 a V6 em nove schemas descartáveis: três para confirmação e seis para estorno. Os testes aguardam o encerramento das threads com timeout e programam a remoção de cada schema gerado em `finally`. O script exige a role `lex_test_runner` e a ausência de privilégio `CONNECT` em `lex_dev` antes de iniciar o Maven.

| Classe | Testes aprovados |
|---|---:|
| AberturaContabilServiceTest | 8 |
| EstornoLancamentoContabilServiceTest | 19 |
| ConfirmacaoLancamentoContabilServiceTest | 18 |
| LancamentoContabilServiceTest | 25 |
| LivroDiarioServiceTest | 5 |
| LivroRazaoServiceTest | 5 |
| PlanoContasServiceTest | 8 |
| ContaContabilRepositoryTest | 4 |
| LancamentoContabilRepositoryTest | 6 |
| PartidaContabilRepositoryTest | 5 |
| PlanoContasRepositoryTest | 1 |
| LexApplicationTests | 1 |
| Total | 105 |

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
| Livro Razão | Incremento 4B validado. Emissão por conta analítica e apuração compartilhada de saldos disponíveis. |
| Saldos iniciais | Incremento 4C validado. Abertura manual por lançamento escriturado no primeiro exercício da empresa. |
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

### Contrato do incremento 4B

`LivroRazaoService.emitir(UUID empresaId, UUID usuarioId, UUID exercicioId, UUID contaId, LocalDate inicio, LocalDate fim)` retorna um `LivroRazao` para uma conta analítica do plano da empresa e um intervalo inclusivo contido no exercício. A seleção de uma conta é obrigatória. O serviço rejeita explicitamente contas sintéticas, evitando agregação com seus descendentes. Os DTOs são imutáveis, com cópia da lista de movimentos, e apresentam a identificação e a natureza cadastrada da conta, os saldos e as partidas individuais com referências, históricos e saldo acumulado.

`ConsultaContabil` centraliza a validação de usuário ativo, titularidade, empresa ativa e não arquivada, exercício e intervalo. A extração preservou a ordem, as condições e as mensagens de erro da validação do Diário. A consulta original manteve os mesmos filtros e ordenação, e os cinco testes do Diário passaram após a mudança.

A sobrecarga de `buscarMovimentosEscriturados` filtra por conta diretamente no banco e lê as partidas desde o início do exercício até o fim do recorte em um único SQL. Preserva movimentos históricos de contas inativas e de exercícios e períodos encerrados. Inclui confirmados, originais estornados e estornos confirmados nas respectivas datas, excluindo rascunhos. A reconciliação automatizada com o Diário compara as partidas da conta na mesma ordem, com seus identificadores, valores e históricos.

`ApuracaoSaldos` concentra a lógica reutilizável no Balancete. Os movimentos anteriores ao início do intervalo, incluindo lançamentos `ABERTURA` escriturados no exercício, compõem o saldo anterior. Os movimentos do intervalo geram os totais de débito e crédito e o saldo acumulado após cada partida. O saldo final é o saldo anterior mais os débitos menos os créditos. Os débitos têm efeito positivo e os créditos, negativo. O sinal determina a natureza efetiva do saldo, devedor, credor ou zerado, independentemente da natureza cadastrada. A apuração usa `BigDecimal` sem arredondar nem restringir o total à precisão de uma partida.

O Razão segue a estratégia de leitura `REPEATABLE_READ` do Diário. Em uma transação externa com isolamento inferior, prevalece o isolamento externo e as validações e a consulta podem observar snapshots diferentes. Saldo anterior, movimentos e saldo final continuam derivados das linhas da mesma instrução SQL.

### Contrato do incremento 4C

`LancamentoContabilService.registrarSaldosIniciais(AberturaContabil abertura)` recebe um DTO imutável com empresa, usuário autenticado, exercício, período, data, histórico e uma lista copiada de `SaldoPorConta`, com conta, débito ou crédito e valor. Retorna `AberturaConfirmada`, com identificador, empresa, exercício, período, número, data, origem e responsável e instante da confirmação.

A operação cria o cabeçalho de origem `ABERTURA`, inclui as partidas e confirma na mesma transação. Reutiliza a autorização, a validação de calendário, contas analíticas e ativas do plano, valores e equilíbrio do núcleo. Exige ao menos duas contas distintas com valores positivos e aceita saldos de natureza contrária à cadastrada. A empresa sem saldos anteriores pode iniciar a escrituração sem abertura. Na migração, a abertura manual é permitida apenas no primeiro exercício cadastrado, em data válida de período aberto, inclusive durante o ano e com contas de resultado, desde que ainda não haja movimentos escriturados. Rascunhos podem existir.

A confirmação de uma abertura deve obter o número 1 na sequência atômica por empresa e exercício. A confirmação manual usa a mesma sequência. Se ela obtiver o primeiro número antes da abertura, a abertura é rejeitada e revertida. Se a abertura obtiver o número 1, a confirmação manual pode prosseguir com a numeração seguinte. O teste concorrente cobre duas aberturas em transações independentes. A disputa entre abertura e confirmação manual foi verificada pelo uso da mesma sequência no código, sem teste simultâneo específico desse par. O teste de falha na confirmação comprova rollback de cabeçalho, partidas e sequência. A regra é aplicada pelo serviço com apoio da sequência e da unicidade da numeração na V6. Escritas diretas que contornem o serviço e a sequência ficam fora dessa proteção.

O estorno preserva a abertura no histórico e a abertura original, mesmo estornada, impede outra abertura manual. O Diário mostra os lançamentos nas respectivas datas contábeis, e o Razão incorpora as partidas aos movimentos e saldos de acordo com o recorte. Ambos os efeitos da abertura foram verificados na suíte.

A integração futura com `auditoria_evento` deve registrar empresa, usuário, operação e referência do lançamento, com os dados aplicáveis na mesma transação. Os valores de abertura de estoque e ativos deverão ser conciliados com os cadastros operacionais para evitar contabilização duplicada quando esses módulos entrarem em uso.

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

O modo padrão do script foi validado nas suítes de 68, 87, 92, 97 e 105 testes. Para JUnit no IntelliJ, use `TEST_DB_*` nas configurações locais All in lex e LexApplicationTests. A atualização dessas configurações ainda não foi confirmada. Uma IDE já aberta não herda variáveis de um novo terminal.

## 5. Próxima etapa — Balancete de Verificação

A próxima etapa é implementar o Balancete de Verificação a partir dos movimentos escriturados e da apuração compartilhada. O transporte patrimonial automatizado permanece pendente. A apuração atual se limita ao exercício selecionado.

**Regra da escrituração efetiva.** Lançamentos `CONFIRMADOS`, inclusive os estornos confirmados, e lançamentos originais `ESTORNADOS` integram a escrituração. Cada um produz efeitos na sua própria data contábil, respeitado o corte do relatório. Lançamentos em `RASCUNHO` ficam fora dos saldos. O original e seu estorno podem produzir efeitos em períodos diferentes.

## 6. Backlog e riscos

- P0: Balancete, demonstrações, continuidade patrimonial e fechamento automatizado. Na sequência, estoque PEPS, ativos e depreciação, conforme a meta de recuperação em [decisoes-e-cronograma.md](decisoes-e-cronograma.md).
- Lançamentos com data anterior à abertura de migração podem duplicar movimentos incorporados aos saldos iniciais. Falta definir como tratar lançamentos retroativos nesse cenário.
- A correção após estorno da abertura deve permanecer possível por meio de lançamentos contábeis apropriados e rastreáveis.
- A DRE deve tratar explicitamente os saldos de resultado importados, distinguindo os saldos acumulados das movimentações efetivamente detalhadas por período.
- A continuidade patrimonial deve distinguir a abertura manual de migração da abertura automatizada de exercícios subsequentes, evitando duplicidade entre o transporte e os lançamentos de abertura.
- P0: entregar link e manual. O enunciado confirma Diário, Razão, Balance (Balancete no planejamento), BP, DRE e os módulos de estoque, ativos e depreciação. Ver [requisitos-academicos.md](requisitos-academicos.md) para orientações de aula, PDFs e a ressalva do modelo de DRE.
- A interface, a geração de PDFs e a integração da auditoria aos fluxos seguem pendentes. A exportação em PDF deverá contemplar o Livro Razão integral, além da emissão individual por conta disponível no serviço atual.
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
- 25/11: incremento 4B concluído antecipadamente, com Livro Razão por conta e apuração compartilhada de saldos. Suíte de 97 testes aprovada.
- 26/11: incremento 4C concluído, com abertura manual de saldos iniciais por lançamento contábil. Suíte de 105 testes aprovada.

Fontes: planejamento de 22/11; relatórios do Codex e logs PostgreSQL/Maven/Git de 25/11 fornecidos pelo desenvolvedor. Este documento não substitui inspeção do código vigente.
