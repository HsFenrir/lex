# LEX — Andamento e passagem de contexto

Atualização em 27/11/2026, após a regressão do checkpoint contábil, a publicação da documentação web e o registro da estratégia de contingência frontend. Apresentação em 30/11/2026.

## 1. Base verificada

- Branch main sincronizada com origin/main e origin/HEAD segundo o log de push fornecido pelo desenvolvedor.
- Incremento 4C publicado em `677d725`. Incremento 5A publicado em `d82f341` (`feat: implement trial balance and hierarchical account aggregation`), com seis arquivos, 512 inserções e nove exclusões. Incremento 5B publicado em `37d2da4`. Incremento 4D publicado em `bba714b`. Os incrementos 6A a 6C foram publicados em `597f34e`, último checkpoint de código validado. O 6D entregou o planejamento da integração web.
- As metas de 25/11 foram integralmente concluídas. Os serviços de Livro Diário e Livro Razão foram antecipados em relação às atividades previstas para 26/11.
- AGENTS.md e documentação inicial publicados em 9d41a94. Incremento 1 publicado em 54a4b39, script de testes em 9fbd7d1, escopo acadêmico confirmado em 1bf3fbc e incremento 2 publicado em 47cf2a8.
- Java 21.0.10, Maven Wrapper (Maven 3.10.1 relatado pelo Codex), Spring Boot 4.1.2, Hibernate ORM 7.4.13.Final, PostgreSQL 18.4, conforme relatórios/logs locais. Conferir pom.xml antes de mudanças.
- No incremento 6, foram compiladas 50 fontes principais e 15 fontes de teste. Flyway V1–V7 validado, schema `public` na V7 e Hibernate em `ddl-auto: validate`.

## 2. Evidência de regressão

No 5A, a execução de 26/11/2026 terminou às 20:10:57 (UTC−03:00), em 01:03 min, com `BUILD SUCCESS`. Foram aprovados **110 testes**, incluindo os cinco de `BalanceteServiceTest`, com zero falhas, erros ou ignorados.

No 5B, o desenvolvedor executou `.\scripts\test.ps1` em 26/11/2026, com término às 22:32:20 (UTC−03:00), duração de 01:03 min e `BUILD SUCCESS`. Os 14 relatórios Surefire confirmam **116 testes, zero falhas, zero erros e zero ignorados**, incluindo seis casos de `DemonstracoesContabeisServiceTest` e os 110 anteriores. As classes dos nove arquivos do incremento foram compiladas após as últimas alterações nas fontes e antes da geração dos relatórios. Os seis métodos de teste das demonstrações coincidem com os casos do Surefire.

No 4D, o desenvolvedor executou `.\scripts\test.ps1` em 26/11/2026, com término às 22:49:19 (UTC−03:00), duração de 01:19 min e `BUILD SUCCESS`. Os 14 relatórios Surefire confirmam **121 testes, zero falhas, zero erros e zero ignorados**. `AberturaContabilServiceTest` teve 13 casos aprovados, incluindo os cinco novos. Os 116 anteriores também passaram. As três classes alteradas foram compiladas após as últimas alterações nas fontes e antes da geração dos relatórios.

Após a correção da fixture de abertura, o desenvolvedor executou `.\scripts\test.ps1` em 26/11/2026. A execução terminou às 23:44:27 (UTC−03:00), em 01:31 min, com `BUILD SUCCESS`. Os 15 relatórios Surefire correspondem às fontes atuais e confirmam **133 testes, zero falhas, zero erros e zero ignorados**. `FechamentoContabilServiceTest` aprovou 12 de 12 casos e `AberturaContabilServiceTest`, 13 de 13. Os 121 casos anteriores foram preservados. As classes e a cópia da V7 no diretório de compilação são posteriores às fontes e anteriores aos relatórios.

Nova regressão do checkpoint `597f34e`, executada pelo desenvolvedor com `.\scripts\test.ps1`, terminou em 27/11/2026 às 00:07:27 (UTC−03:00), em 01:13 min, com `BUILD SUCCESS`. A conferência dos 15 relatórios Surefire confirmou **133 testes, zero falhas, zero erros e zero ignorados**. A última execução usou PostgreSQL 18.4 e schema de testes V7.

A suíte do incremento 6 usou o perfil `test` no PostgreSQL 18.4 em `jdbc:postgresql://127.0.0.1:5432/lex_test`. Foram validadas sete migrations, com o schema `public` na V7 e 28 schemas descartáveis. A execução anterior aplicou a V7 sobre o `public` que estava na V6. A suíte cobre também o upgrade com exercício legado em schema descartável e a instalação desde a V1. Os testes aguardam o encerramento das threads com timeout e removem os schemas em `finally`. O script exige a role `lex_test_runner` e a ausência de privilégio `CONNECT` em `lex_dev` antes de iniciar o Maven.

| Classe | Testes aprovados |
|---|---:|
| AberturaContabilServiceTest | 13 |
| BalanceteServiceTest | 5 |
| EstornoLancamentoContabilServiceTest | 19 |
| FechamentoContabilServiceTest | 12 |
| ConfirmacaoLancamentoContabilServiceTest | 18 |
| DemonstracoesContabeisServiceTest | 6 |
| LancamentoContabilServiceTest | 25 |
| LivroDiarioServiceTest | 5 |
| LivroRazaoServiceTest | 5 |
| PlanoContasServiceTest | 8 |
| ContaContabilRepositoryTest | 4 |
| LancamentoContabilRepositoryTest | 6 |
| PartidaContabilRepositoryTest | 5 |
| PlanoContasRepositoryTest | 1 |
| LexApplicationTests | 1 |
| Total | 133 |

Os cenários negativos incluem violações esperadas das chaves estrangeiras `fk_conta_pai` e `fk_conta_reduzida` e das regras de unicidade `uq_lancamento_estorno_unico` e `uq_lancamento_numero_empresa_exercicio`. Os testes respectivos passaram. A colisão de número foi provocada após a alocação para verificar que o lançamento permanece em rascunho e a atualização do contador é revertida.

O teste de rollback do estorno provocou uma falha na transição do original após o `flush` da confirmação do estorno, por meio da constraint `teste_bloquear_estornado` em schema descartável. A verificação em outra transação confirmou que o original permaneceu `CONFIRMADO`, o estorno voltou a `RASCUNHO` sem número, a reserva foi mantida e a sequência foi revertida.

Na validação do incremento 1, em 25/11 às 01:46:32 (UTC−03:00), `.\mvnw.cmd -o clean test` concluiu com 50 testes aprovados. As falhas de renderização do PSReadLine na inserção manual do bloco não afetaram esse resultado.

## 3. Implementação disponível e pontos de inspeção

| Área | Estado |
|---|---|
| Usuário, empresa, auditoria | Titularidade validada nos serviços. Fechamento e continuidade têm auditoria localizada. Autenticação e auditoria geral seguem pendentes. |
| Plano de contas | Modelo, contas, serviço e repositórios existentes; testes aprovados. |
| Exercício/período | Entidades, repositórios e constraints existentes. |
| Lançamento/partida | Entidades e repositórios, mapeamentos compostos e testes de persistência aprovados. |
| Sequência | Alocação atômica validada, com contador independente por empresa e exercício. |
| Serviços de lançamento | Incrementos 1 a 3 validados. Criação de rascunho, inclusão e remoção de partidas manuais, confirmação com numeração atômica e estorno completo disponíveis. |
| Livro Diário | Incremento 4A validado. Serviço de emissão e consulta compartilhada de partidas escrituradas disponíveis. |
| Livro Razão | Incremento 4B validado. Emissão por conta analítica e apuração compartilhada de saldos disponíveis. |
| Saldos iniciais | Abertura manual no primeiro exercício da empresa. O 4D valida o limite temporal da migração em cada confirmação. |
| Balancete de Verificação | Incremento 5A publicado. Apuração por conta e agregação pela hierarquia do plano. |
| Balanço Patrimonial e DRE | Incremento 5B validado. Emissão por data-base e intervalo, respectivamente. |
| Fechamento e continuidade | Incrementos 6A, 6B e 6C validados na camada contábil. Integração com os módulos operacionais pendente. |
| API/frontend | Spring MVC e Thymeleaf como dependências. Controllers, autenticação e telas pendentes. O 6D planejou 36 rotas HTTP, com 16 serviços disponíveis e 20 operações dependentes de implementação adicional. |

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

As reversões de lançamentos de origem operacional dependem da integração com estoque, ativos e depreciação. O fluxo genérico rejeita estorno do encerramento técnico e da abertura automatizada. A auditoria geral dos estornos permanece pendente.

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

A confirmação de uma abertura deve obter o número 1 na sequência atômica por empresa e exercício. A confirmação manual usa a mesma sequência. O 4C testou duas aberturas concorrentes em transações independentes e comprovou o rollback de cabeçalho, partidas e sequência após falha na confirmação. O 4D acrescentou testes diretos da disputa entre abertura e confirmação manual.

O estorno preserva a abertura no histórico e a abertura original, mesmo estornada, impede outra abertura manual. O Diário mostra os lançamentos nas respectivas datas contábeis, e o Razão incorpora as partidas aos movimentos e saldos de acordo com o recorte. Ambos os efeitos da abertura foram verificados na suíte.

A integração futura com `auditoria_evento` deve registrar empresa, usuário, operação e referência do lançamento, com os dados aplicáveis na mesma transação. Os valores de abertura de estoque e ativos deverão ser conciliados com os cadastros operacionais para evitar contabilização duplicada quando esses módulos entrarem em uso.

### Contrato do incremento 5A

`BalanceteService.emitir(UUID empresaId, UUID usuarioId, UUID exercicioId, LocalDate inicio, LocalDate fim)` retorna um `Balancete` imutável com empresa, exercício, intervalo inclusivo, linhas do plano completo, seis totais monetários e indicadores de equilíbrio do saldo anterior, dos movimentos e do saldo final. Reutiliza `ConsultaContabil` para autorização, isolamento e validação das datas.

A projeção escalar do plano inclui contas zeradas e inativas. Uma consulta de movimentos agrupados no banco por conta e tipo de partida separa o saldo anterior dos movimentos do intervalo, limitado ao exercício e às datas contábeis. Aplica o filtro compartilhado de escrituração, incluindo aberturas, originais estornados e estornos confirmados. `ApuracaoSaldos` usa `BigDecimal`, sem arredondamento nem limite de precisão de uma partida. O saldo final é o saldo anterior mais débitos menos créditos. Seu sinal determina a natureza efetiva.

As sintéticas recebem os saldos líquidos de seus descendentes pelo vínculo persistido `conta_pai_id`, de baixo para cima. Os totais gerais somam apenas analíticas e separam os saldos devedores e credores. Diferenças monetárias aparecem nos indicadores sem ajuste artificial. Hierarquia cíclica ou com pai inválido interrompe a emissão. Os testes reconciliam os totais dos movimentos com o Diário e os saldos analíticos com o Razão.

### Contratos do incremento 5B

- `BalancoPatrimonialService.emitir(UUID empresaId, UUID usuarioId, UUID exercicioId, LocalDate dataBase)` retorna `BalancoPatrimonial` com seções, contas analíticas, subtotais hierárquicos, saldo contábil assinado, valores de apresentação, diferença patrimonial e indicador de equilíbrio. Reutiliza o saldo final do Balancete na data-base, incluindo abertura e preservando a escrituração histórica. Totais partem das contas analíticas. Ativos usam o sinal contábil e passivos e PL usam o sinal invertido para apresentação. Contas redutoras permanecem na classificação correspondente, com o vínculo à conta reduzida, sem reclassificação por sinal.
- `DreService.emitir(UUID empresaId, UUID usuarioId, UUID exercicioId, LocalDate inicio, LocalDate fim)` retorna `Dre` com rubricas e subtotais verticais, contas de resultado e valores por origem em intervalo inclusivo. Usa créditos menos débitos, preservando os efeitos econômicos. Outras receitas e despesas são exibidas em rubricas próprias, pois o modelo reúne itens operacionais e ganhos ou perdas de alienação nessas classificações. Ambos os DTOs usam records e listas copiadas.

O BP usa `ATIVO_CIRCULANTE`, `ATIVO_NAO_CIRCULANTE`, `PASSIVO_CIRCULANTE`, `PASSIVO_NAO_CIRCULANTE` e `PATRIMONIO_LIQUIDO`. A DRE usa `RECEITA_BRUTA`, `DEDUCOES_RECEITA`, `CUSTOS`, `DESPESAS_OPERACIONAIS`, `RESULTADO_FINANCEIRO`, `OUTRAS_RECEITAS`, `OUTRAS_DESPESAS` e `TRIBUTOS_SOBRE_RESULTADO`. Os grupos patrimoniais são `ATIVO`, `PASSIVO` e `PATRIMONIO_LIQUIDO`. Os de resultado são `RECEITA`, `CUSTO` e `DESPESA`. As raízes sintéticas do modelo têm classificação nula.

Na falta de classificação explícita, a resolução usa ancestrais sintéticos do mesmo grupo. O BP também pode usar o vínculo com a conta reduzida e a correspondência inequívoca do grupo PL. Uma conta relevante sem classificação suficiente interrompe a emissão com código e UUID no diagnóstico.

O PL apresentado soma o PL escriturado e o resultado líquido ainda remanescente nas contas de resultado na data-base. Lucro aumenta o PL e prejuízo o reduz. Uma futura transferência escriturada reduz o remanescente e evita duplicidade. A diferença é calculada por ativo menos passivo menos PL. A suíte cruza os saldos do BP com o Razão e os movimentos da DRE com o Balancete, além de conferir a correspondência do resultado remanescente com o BP.

A DRE agrupa movimentos por conta, tipo e origem, preservando também a origem do original estornado. Separa movimentos econômicos, saldos importados na abertura, estornos de abertura e acumulado do exercício até o fim do recorte. O saldo importado entra no resultado demonstrado quando o recorte começa no início do exercício e alcança a abertura. Nos recortes parciais que atingem esse histórico, o relatório sinaliza a falta de detalhamento e expõe a abertura separadamente quando ela está no intervalo. Os estornos afetam as respectivas datas contábeis. A DRE usa modelo próprio aprovado pelo desenvolvedor. A disponibilidade do modelo do professor permanece como ressalva acadêmica.

A V7 acrescentou `ENCERRAMENTO`. A consulta da DRE exclui os movimentos técnicos identificados por essa origem, inclusive eventuais reversões identificadas pela origem do original. Os movimentos econômicos, os estornos ordinários e o tratamento da abertura de migração são preservados.

As três emissões reutilizam as regras de autorização e escrituração e usam agregação em `BigDecimal`, sem arredondamento e sem consultas N+1. Em chamadas independentes, a transação de leitura `REPEATABLE_READ` mantém o snapshot entre consultas. Quando há transação externa com isolamento inferior, prevalece o isolamento externo.

### Proteção temporal do incremento 4D

`LancamentoContabilService.registrarConfirmacao` valida a data contábil após a alocação provisória do número e antes da confirmação. O repositório consulta a data da abertura persistida na mesma empresa e no mesmo exercício, incluindo aberturas `CONFIRMADAS` e `ESTORNADAS`. Rascunhos preexistentes passam pela mesma validação. São permitidas operações na data da abertura e retroativos legítimos em períodos abertos a partir desse marco. A abertura tem o número 1 e precede as operações confirmadas na mesma data. Sem abertura, a confirmação segue o fluxo anterior.

A sequência da empresa e do exercício serializa as operações sem acrescentar bloqueios nem mudar sua ordem. Em `READ COMMITTED`, a consulta executada após o UPSERT obtém um novo snapshot e vê a abertura que foi confirmada enquanto a operação aguardava a sequência. Com snapshot fixo, uma alteração concorrente da sequência fora do snapshot pode causar falha de serialização.

Três testes em transações independentes observaram o bloqueio real na sequência antes de liberar o commit da primeira operação. Quando a abertura obteve precedência, a confirmação anterior ao marco foi rejeitada e a confirmação na mesma data recebeu o número 2. Quando a confirmação manual obteve precedência, a abertura foi rejeitada. As verificações persistentes confirmaram que o rollback devolve o número provisório, preserva o rascunho manual rejeitado sem dados de confirmação e evita resíduos da abertura rejeitada. Os demais testes cobriram o marco após estorno, o isolamento por empresa e exercício e a aceitação de retroativos válidos.

A proteção fica na camada de aplicação. Escritas diretas que ignorem o serviço podem contorná-la. Os futuros módulos operacionais devem reutilizar o núcleo de confirmação. O transporte patrimonial deve distinguir a abertura automatizada da abertura manual de migração, que estabelece o limite temporal.

### Contratos dos incrementos 6A, 6B e 6C

- `FechamentoContabilService.encerrarPeriodo(UUID empresaId, UUID usuarioId, UUID exercicioId, UUID periodoId)` retorna `PeriodoEncerrado` com exercício, período e data final. Exige exercício e período abertos, meses anteriores cadastrados encerrados, ausência de rascunhos e escrituração equilibrada. Preserva os saldos de resultado. Dezembro fica reservado ao fechamento anual.
- `FechamentoContabilService.encerrarExercicio(UUID empresaId, UUID usuarioId, UUID exercicioId)` retorna `ExercicioEncerrado` com os exercícios de origem e destino, o resultado líquido e os IDs dos lançamentos técnicos quando gerados. Exige dezembro aberto, meses anteriores cadastrados encerrados, ausência de rascunhos, equilíbrio e classificação suficiente.

O anual apura pelo Balancete os saldos analíticos até o fim do exercício e inverte as partidas para zerar as contas de resultado. A contrapartida usa **Lucros Acumulados (3.3.001)** ou **Prejuízos Acumulados (3.3.002)** pelo vínculo com a conta do modelo padrão versão 1. Resultado líquido zero dispensa a contrapartida de PL. Contas de resultado já zeradas dispensam o lançamento técnico. Saldos agregados superiores ao limite de `NUMERIC(19,2)` são divididos em partidas representáveis, sem arredondamento.

A migration `V7__registrar_encerramento_e_continuidade.sql` admite a origem `ENCERRAMENTO` e garante sua unicidade por empresa e exercício. Adiciona `exercicio_origem_id` com unicidade e chave estrangeira no escopo da empresa. As migrations V1–V6 e os triggers de confirmação e imutabilidade foram preservados. O lançamento técnico é confirmado antes de encerrar dezembro e o exercício. A suíte confirma que a DRE permanece equivalente antes e depois do fechamento, que o BP mantém o equilíbrio com o resultado já escriturado no PL e que Diário, Razão e Balancete incluem os movimentos técnicos.

A continuidade cria ou completa os 12 períodos do sucessor imediato e rejeita um destino incompatível ou com escrituração. Transporta apenas saldos analíticos de Ativo, Passivo e PL, inclusive redutoras e saldos invertidos. Contas inativas com saldo a escriturar bloqueiam a operação. Quando há saldo a transportar, a abertura usa a origem `ABERTURA`, o primeiro dia do exercício e o número 1. O vínculo distingue a abertura automatizada da abertura manual de migração e registra a continuidade mesmo quando o saldo zero dispensa um lançamento. Um exercício posterior com predecessor cadastrado exige esse vínculo antes de confirmar operações. A abertura manual segue restrita ao primeiro exercício.

O fechamento usa `READ COMMITTED` e bloqueia os períodos em ordem cronológica, da origem para o destino, antes dos exercícios. Em seguida, valida autorização, contas e sequência no fluxo de confirmação. A verificação de rascunhos consulta a escrituração sem bloquear lançamentos de terceiros. Os testes em transações independentes observaram o bloqueio em disputas com confirmação manual, criação de rascunho, estorno, outro fechamento anual e confirmação no sucessor durante o transporte.

O fechamento anual e o transporte gravam lançamentos, partidas, sequências, vínculo, estados e eventos em uma transação. O teste de falha forçada na confirmação da abertura verificou o rollback do conjunto. A auditoria localizada em `auditoria_evento` registra `ENCERRAR_PERIODO`, `ENCERRAR_EXERCICIO` e `TRANSPORTAR_SALDOS`, com empresa, usuário, data contábil e referências aplicáveis.

A primeira execução do incremento teve 132 aprovações e um erro. O teste de abertura preservava corretamente o rascunho manual de 14/05/2026, rejeitado por ser anterior ao marco de migração de 15/05/2026, mesmo após o estorno da abertura. O rascunho passou a bloquear o fechamento de maio na preparação da continuidade. Depois das assertions de preservação, a fixture remove as partidas pelo serviço e exclui somente o cabeçalho via JPA, na ordem exigida pelas constraints e com os triggers ativos. A correção preservou as assertions e as regras de produção.

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

O modo padrão do script foi validado nas suítes de 68, 87, 92, 97, 105, 110, 116, 121 e 133 testes. Para JUnit no IntelliJ, use `TEST_DB_*` nas configurações locais All in lex e LexApplicationTests. A atualização dessas configurações ainda não foi confirmada. Uma IDE já aberta não herda variáveis de um novo terminal.

## 5. Próxima etapa e contingência frontend — 27/11/2026

As metas contábeis de 26/11/2026 foram concluídas. O desenvolvedor pretende trabalhar até as 02h00 de 27/11/2026 (horário de Brasília) para antecipar a próxima atividade, **incremento 7A — Controle de estoque PEPS**. Ativos e depreciação linear seguem no cronograma de módulos operacionais. Para retomar em nova sessão, usar os contratos da seção 3 e a suíte validada da seção 2 como base. A integração operacional deve reutilizar o núcleo de confirmação, incluindo o limite de migração e o bloqueio por calendário.

**Regra da escrituração efetiva.** Lançamentos `CONFIRMADOS`, inclusive os estornos confirmados, e lançamentos originais `ESTORNADOS` integram a escrituração. Cada um produz efeitos na sua própria data contábil, respeitado o corte do relatório. Lançamentos em `RASCUNHO` ficam fora dos saldos. O original e seu estorno podem produzir efeitos em períodos diferentes.

O 6E registra a decisão do desenvolvedor de manter duas iniciativas de frontend. O **Plano A** fica com o colaborador e o **Plano B** com o desenvolvedor principal, como contingência. As duas versões compartilham o backend, os contratos de API, as regras contábeis e a identidade oficial. Para o Plano B, a preferência é Thymeleaf, HTML5, CSS3 e JavaScript, com Spring MVC, API REST, sessão no servidor e uma mesma origem HTTPS em um único JAR. O Plano B prioriza cobertura funcional, acessibilidade, integração, estabilidade e prazo. [Integração web](integracao-web.md).

O Plano A pode avançar em paralelo ao backend. O Plano B só começa após a conclusão e validação integral do backend. As branches propostas são `feat/frontend-colaborador` e `feat/frontend-contingencia`. As fixtures fictícias devem ser identificadas, os diffs revisados e a integração na `main` controlada. A versão definitiva será selecionada antes da estabilização final pela cobertura dos requisitos e pelo funcionamento demonstrado em fluxos reais. [Guia frontend](guia-frontend.md).

O manual PDF acadêmico 1.0, de outubro de 2026, é a referência normativa da identidade visual. O PNG principal corresponde à imagem raster da página 3. Tokens e componentes adicionais são propostas técnicas. Matriz vetorial, transparência, versões finais monocromática e negativa e favicon continuam pendentes. [Manual oficial](Manual_Identidade_Visual_LEX.pdf) · [Identidade visual para web](identidade-visual.md).

## 6. Backlog e riscos

- P0: implementar estoque PEPS, controle de ativos e depreciação linear e integrar suas verificações ao fechamento. A integração operacional do fechamento está pendente. [Decisões e cronograma](decisoes-e-cronograma.md).
- Conciliar os saldos contábeis de abertura com os controles operacionais de estoque e ativos, evitando lançamentos duplicados.
- Implementar uma operação autorizada, transacional e auditável para cancelar ou excluir rascunhos manuais.
- Definir a regularização de contas inativas com saldos relevantes antes de encerrar ou transportar seus valores.
- Implementar a auditoria geral dos fluxos. Os eventos do fechamento e da continuidade já são registrados localmente.
- Concluir API, autenticação, interface web, PDFs, implantação e manual e executar a validação ponta a ponta.
- A correção após estorno da abertura deve permanecer possível por meio de lançamentos contábeis apropriados e rastreáveis.
- A continuidade patrimonial deve distinguir a abertura manual de migração da abertura automatizada de exercícios subsequentes, evitando duplicidade entre o transporte e os lançamentos de abertura.
- P0: entregar link e manual. O enunciado confirma Diário, Razão, Balance (Balancete no planejamento), BP, DRE e os módulos de estoque, ativos e depreciação. Ver [requisitos-academicos.md](requisitos-academicos.md) para orientações de aula, PDFs e a ressalva do modelo de DRE.
- A exportação em PDF deverá contemplar o Livro Razão integral, além da emissão individual por conta disponível no serviço atual.
- O projeto adotará um modelo próprio de DRE, conforme a decisão já registrada. A implementação pode avançar enquanto permanece a ressalva acadêmica sobre a disponibilidade do modelo do professor e eventuais diferenças de formato. [Requisitos acadêmicos](requisitos-academicos.md).
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
- 26/11: incremento 5A publicado em `d82f341`, com Balancete de Verificação e agregação hierárquica. Suíte de 110 testes aprovada.
- 26/11: incremento 5B concluído, com Balanço Patrimonial e DRE. Suíte de 116 testes aprovada.
- 26/11: incremento 4D concluído, com proteção temporal da abertura e teste direto da concorrência com confirmação manual. Suíte de 121 testes aprovada.
- 26/11: incrementos 6A, 6B e 6C concluídos na camada contábil, com fechamento mensal, fechamento anual, continuidade patrimonial e auditoria localizada. V7 validada e suíte de 133 testes aprovada após correção da fixture de abertura.

- 27/11: regressão de 133 testes no checkpoint `597f34e` e consolidação documental do 6D, da identidade visual oficial e das orientações de colaboração frontend.
- 27/11: documentação consolidada publicada em `b702b72`. O 6E registrou os planos A e B, o tratamento binário de PDF e PNG e a antecipação do 7A como próxima atividade.

Fontes: planejamento de 22/11; relatórios do Codex e logs PostgreSQL/Maven/Git de 25/11 fornecidos pelo desenvolvedor. Este documento não substitui inspeção do código vigente.
