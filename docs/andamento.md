# LEX — Andamento e passagem de contexto

Atualização: 25/11/2026, após publicação do checkpoint. Apresentação: 30/11/2026.

## 1. Base verificada

- Branch main sincronizada com origin/main e origin/HEAD segundo o log de push fornecido pelo desenvolvedor.
- Checkpoint 8b8eb2d — test: validate accounting persistence and isolate test database; 14 arquivos, 789 inserções e 4 exclusões.
- Commit anterior 08a584e — feat: adicionar entidades de lancamentos contabeis.
- AGENTS.md criado e lido pelo Codex; ainda não rastreado no último git status. Documentação desta consolidação ainda precisa ser adicionada ao repositório.
- Java 21.0.10, Maven Wrapper (Maven 3.10.1 relatado pelo Codex), Spring Boot 4.1.2, Hibernate ORM 7.4.13.Final, PostgreSQL 18.4, conforme relatórios/logs locais. Conferir pom.xml antes de mudanças.
- 32 fontes principais e 6 fontes de teste compiladas, 10 interfaces JPA detectadas. Flyway V1–V5 validado; schema public na versão 5; Hibernate ddl-auto: validate.

## 2. Evidência de regressão

Execução .\mvnw.cmd clean test em 25/11/2026, encerrada às 00:57:03 (-03:00), duração total 1 min 55 s. BUILD SUCCESS, 25 testes, zero falhas, erros ou ignorados. Perfil test ativo; Flyway e Hibernate apontaram jdbc:postgresql://localhost:5432/lex_test.

| Classe | Testes aprovados |
|---|---:|
| PlanoContasServiceTest | 8 |
| ContaContabilRepositoryTest | 4 |
| LancamentoContabilRepositoryTest | 6 |
| PartidaContabilRepositoryTest | 5 |
| PlanoContasRepositoryTest | 1 |
| LexApplicationTests | 1 |
| Total | 25 |

Violações SQLState 23503 de fk_conta_pai e fk_conta_reduzida e SQLState 23505 de uq_lancamento_estorno_unico ocorreram em classes aprovadas; coerentes com cenários negativos. O relatório do Codex descreveu teste de estorno com original confirmado e partidas equilibradas, período posterior aberto, estorno em rascunho sem número e rejeição específica de segundo estorno. Isso não comprova o futuro serviço transacional de confirmação/estorno nem concorrência.

## 3. Implementação disponível e pontos de inspeção

| Área | Estado |
|---|---|
| Usuário, empresa, auditoria | Estrutura de domínio/persistência existente; fluxos completos de acesso/autorização ainda devem ser verificados. |
| Plano de contas | Modelo, contas, serviço e repositórios existentes; testes aprovados. |
| Exercício/período | Entidades, repositórios e constraints existentes. |
| Lançamento/partida | Entidades e repositórios, mapeamentos compostos e testes de persistência aprovados. |
| Sequência | Tabela sequencia_lancamento existente na V5; serviço de alocação concorrente pendente. |
| Serviços de lançamento | Pendente; não confundir métodos de domínio e teste de integração com serviço pronto. |
| API/frontend | Estado completo a inspecionar; integração essencial pendente. |

Pontos de entrada conhecidos (confirmar assinaturas na versão atual): LancamentoContabilRepository.buscarParaAlteracao(id, empresaId), com PESSIMISTIC_WRITE; consultas de lançamento por empresa/exercício/período; PartidaContabilRepository por lançamento e plano; ContaContabilRepository por plano; ExercicioContabilRepository e PeriodoContabilRepository por escopo. LancamentoContabil.criarEstorno recebe original, exercício/período de destino, criador, data e histórico. TipoConta: SINTETICA, ANALITICA. PartidaContabil valida rascunho, mesmo plano, positividade e escala; validação de precisão total e de conta ativa/analítica ainda demanda tratamento. Consultar migrations V4/V5 e entidades antes de editar.

## 4. Ambiente de execução

| Perfil | JDBC | Role | Variáveis |
|---|---|---|---|
| Desenvolvimento | jdbc:postgresql://localhost:5432/lex_dev | lex | DB_URL, DB_USERNAME, DB_PASSWORD |
| Testes | jdbc:postgresql://localhost:5432/lex_test | lex_test_runner | TEST_DB_URL, TEST_DB_USERNAME, TEST_DB_PASSWORD |

application.yaml mantém URL local padrão, exige usuário/senha. application-test.yaml exige as três variáveis TEST_DB_* sem fallbacks. Flyway habilitado e sem datasource independente identificado. 13 tabelas e 3 funções de lex_test transferidas à role de testes, migrations V1–V5 preservadas. CONNECT cruzado bloqueado; conexão real da role de testes ao lex_test passou e ao lex_dev foi negada por ausência de CONNECT. Isso não prova isolamento em relação a todos os outros bancos do servidor. Não executar testes como postgres.

### Executar a suíte no PowerShell

Usar sessão dedicada. Conferir previamente ausência de sobrescritas Spring, Maven/JVM e Flyway (inclusive opções do IntelliJ e arquivos de configuração do Maven). Nunca usar senha de desenvolvimento no teste.

```powershell
$cred = Get-Credential -UserName 'lex_test_runner' -Message 'PostgreSQL de testes LEX'
try {
    if ($cred.UserName -cne 'lex_test_runner') { throw 'Role de teste incorreta.' }
    $env:TEST_DB_URL = 'jdbc:postgresql://localhost:5432/lex_test'
    $env:TEST_DB_USERNAME = $cred.UserName
    $env:TEST_DB_PASSWORD = $cred.GetNetworkCredential().Password
    $env:PGPASSWORD = $env:TEST_DB_PASSWORD
    try {
        $identidade = & 'C:\Program Files\PostgreSQL\18\bin\psql.exe' -X -w -h localhost -p 5432 -d lex_test -U lex_test_runner -v ON_ERROR_STOP=1 -At -c "SELECT current_database(), current_user, inet_server_port(), has_database_privilege(current_user, 'lex_dev', 'CONNECT');"
        if ($LASTEXITCODE -ne 0 -or $identidade -cne 'lex_test|lex_test_runner|5432|f') { throw 'Destino ou isolamento incorreto.' }
    } finally { Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue }
    .\mvnw.cmd clean test
    if ($LASTEXITCODE -ne 0) { throw 'Suíte falhou; revisar target/surefire-reports.' }
} finally {
    Remove-Item Env:TEST_DB_URL, Env:TEST_DB_USERNAME, Env:TEST_DB_PASSWORD -ErrorAction SilentlyContinue
    Remove-Variable cred -ErrorAction SilentlyContinue
}
```

O script verifica a conexão psql, não é garantia contra todas as possíveis sobrescritas de configuração do Spring. Conferir também o destino nos logs. Não enviar a senha pelo chat nem armazená-la no repositório. JUnit no IntelliJ: usar TEST_DB_* nas configurações locais All in lex e LexApplicationTests; sua atualização ainda não foi confirmada. Variáveis de um terminal não são automaticamente herdadas pela IDE já aberta.

## 5. Próxima tarefa autorizável: serviço de rascunhos e partidas

1. Ler AGENTS.md, estes documentos, entidades/repositórios/migrations pertinentes e verificar git status. Não pedir arquivos presentes localmente.
2. Propor contrato do LancamentoContabilService coerente com o projeto e implementar após autorização: criar rascunho; incluir/remover partidas; verificar titularidade do usuário sobre empresa, coerência de exercício, período e plano, empresa ativa, data e estado aplicáveis.
3. Para mutações, bloquear o lançamento no escopo da empresa, validar RASCUNHO e garantir que partida pertence a ele. Conta analítica ativa e do plano correto. Validar limite NUMERIC(19,2) e testar valores-limite; não alterar migrations para acomodar código.
4. Testar caminhos válidos e inválidos, inclusive duas empresas, conta sintética/inativa, conta ou período alheio, valores inválidos, tentativa de alterar confirmado, remoção de partida alheia e rollback. Não implementar confirmação/estorno completo neste incremento sem autorização específica.
5. Rodar suíte no banco de testes autorizado, revisar diff e informar resultados e aptidão para commit. Se credenciais indisponíveis ao Codex, desenvolvedor executa localmente e fornece log sem segredos.

## 6. Backlog e riscos

- P0: serviço de rascunhos/partidas (atividade de 24/11 atrasada); confirmação atômica e concorrência (25/11); estorno completo (26/11); API/frontend (27–28/11); estabilização (29/11).
- P0: confirmar especificação acadêmica integral e relatórios obrigatórios; não deixar eventual requisito do professor fora da entrega por falta de documentação.
- P1: cobertura de isolamento entre empresas e autorização do usuário; precisão monetária; validações de conta ativa/analítica; proteção adicional contra destino errado antes do contexto Spring.
- P1 segurança: fallbacks antigos de credenciais foram removidos, mas valores anteriores podem persistir no histórico Git. Classificar e rotacionar se reais; remoção do arquivo atual não elimina exposição histórica. Não reescrever histórico sem aprovação.
- P2: avisos de repositórios Maven (timeouts/401), agente dinâmico Mockito, templates Thymeleaf ausentes; não bloqueiam checkpoint. Rever quando pertinente, sem ampliar escopo.

## 7. Registro de mudanças

- 22/11: planejamento original; 14 testes aprovados e 8 interfaces de repositório relatadas.
- 25/11: Codex integrado; AGENTS.md validado; teste de estorno alinhado à assinatura de seis argumentos; bancos e roles segregados; YAMLs sem fallback de senha; 25 testes aprovados; commit 8b8eb2d publicado.
- Próximo: versionar documentação após revisão e iniciar serviço. Atualizar esta seção a cada incremento com data, arquivos, testes, commit, riscos e próximo passo. Não declarar funcionalidade concluída sem evidência.

Fontes: planejamento de 22/11; relatórios do Codex e logs PostgreSQL/Maven/Git de 25/11 fornecidos pelo desenvolvedor. Este documento não substitui inspeção do código vigente.
