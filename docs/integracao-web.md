# LEX — Preparação da integração web

Planejamento do incremento 6D, sobre o checkpoint `597f34e`. Os contratos abaixo são propostas. A entrega acadêmica continua prevista para 30/11/2026.

## Estrutura atual

| Área | Verificado no repositório e no terminal |
| --- | --- |
| Backend | Java 21 e Spring Boot 4.1.2 no `pom.xml`, com starters MVC, Thymeleaf, Validation, JPA e Flyway, driver PostgreSQL e Maven Wrapper. |
| Camada web | Nenhum controller MVC ou REST identificado. `src/main/resources/templates` e `src/main/resources/static` existem e estão vazios. Nenhum projeto frontend separado ou manifesto Node identificado. |
| Identidade e empresa | `Usuario` persiste e-mail, `senhaHash` e estado ativo. `Empresa` tem titular, estado ativo e dados de arquivamento. `EmpresaRepository.findByIdAndUsuarioId` já apoia a autorização. O repositório de usuários ainda carece de busca por e-mail para login. Faltam Spring Security, provedor de autenticação, gestão de sessão e serviços de cadastro/gestão de empresas. |
| Contabilidade | Serviços de plano, lançamentos, estorno, abertura, cinco relatórios e fechamento estão disponíveis. `ConsultaContabil` valida o contexto para consultas. Há records de abertura, relatórios e fechamento. Alguns comandos retornam entidades e precisarão de DTOs de saída na camada web. |
| Auditoria | A tabela `auditoria_evento` existe e recebe eventos localizados no fechamento e transporte. A consulta autorizada e a cobertura dos outros fluxos estão pendentes. |
| Perfis | `application.yaml` usa `DB_URL`, `DB_USERNAME` e `DB_PASSWORD`, com URL padrão de desenvolvimento. O perfil de teste exige `TEST_DB_URL`, `TEST_DB_USERNAME` e `TEST_DB_PASSWORD`. Flyway habilitado, Hibernate em `validate` e `open-in-view: false`. O schema validado é V7. |
| Ferramentas | Java 21.0.10, Node 24.22.1 e npm 11.19.1 acessíveis, com Node e npm fornecidos pelo ambiente da IDE. Clientes Docker e Compose encontrados. Não foi verificada a disponibilidade do daemon nem de um ambiente externo. |
| Implantação | Nenhum Dockerfile, Compose, pipeline ou manifesto de hospedagem versionado foi encontrado. O JAR executável do Spring Boot e o PostgreSQL são a base reaproveitável. |

A base funcional é o núcleo já validado com 133 testes. O único material produzido no 6D é a documentação. [Andamento](andamento.md).

## Arquitetura recomendada

**Spring MVC + Thymeleaf para a interface da entrega, com API REST e páginas sob a mesma origem HTTPS.** Renderizar navegação, formulários e tabelas no servidor. Usar JavaScript pontual para adicionar partidas, atualizar totais visíveis ou buscar dados pela API de mesma origem, mantendo cálculo e validação definitivos no servidor. Controllers de página e REST devem chamar os mesmos serviços de aplicação. A renderização de página não precisa fazer chamadas HTTP internas para a própria API. O Spring Boot já oferece suporte aos templates e recursos estáticos no classpath. [Referência do Spring Boot](https://docs.spring.io/spring-boot/reference/web/servlet.html).

| Critério | MVC e Thymeleaf | React + TypeScript |
| --- | --- | --- |
| Prazo e integração | Aproveita as dependências existentes. Um build e formulários server-side simplificam o primeiro fluxo integrado. | Node está disponível, mas seria preciso criar o projeto, build, roteamento, clientes HTTP, formulários, tratamento de sessão e integração dos artefatos. |
| Manutenção | Adequado aos fluxos com formulários e relatórios. Mantém regras no Java e requer pouco código no navegador. | Tipagem e componentes favorecem interações complexas. Requer manter dependências e sincronizar tipos com o contrato HTTP. |
| Sessão | Sessão no servidor, CSRF em formulários e em requisições JavaScript. | Pode compartilhar a mesma sessão e origem se o build estático for servido pelo Spring ou por proxy. Exige tratar token CSRF, renovação de sessão e navegação no cliente. |
| Acessibilidade | HTML semântico e navegação de página oferecem uma base simples para teclado, foco e mensagens de erro. | Também suporta HTML semântico. Estados assíncronos, foco e transições de rota precisam de atenção explícita. |
| Implantação | Código Java, templates e recursos em um único JAR. | Uma origem continua possível, mas é necessário empacotar o build estático, definir o fallback de rotas e preservar a distinção entre rotas da interface e da API. |

A proposta usa HTML5, CSS3 e JavaScript com Thymeleaf e Spring MVC. React + TypeScript continua como opção futura. A escolha para a entrega depende de alinhamento entre o responsável e o colaborador frontend. [Decisões e cronograma](decisoes-e-cronograma.md) · [Guia frontend](guia-frontend.md).

## Contrato HTTP preliminar

- Prefixo comum `/api/v1`. Na tabela, `{E}` expande para `/empresas/{e}`, `{X}` para `{E}/exercicios/{x}` e `{L}` para `{E}/lancamentos/{l}`. Os identificadores `e`, `x`, `p`, `l` e `s` são UUIDs de empresa, exercício, período ou partida conforme a rota, lançamento e estorno. `?` indica parâmetro opcional. GET sem código indicado retorna 200.
- **T** exige sessão ativa e titularidade. **A** acrescenta empresa ativa e não arquivada. Toda referência de exercício, período, plano, conta, lançamento e partida deve ser validada no escopo autorizado. A edição e o arquivamento exigem autorização no serviço. A seleção de empresa na tela só define o contexto de navegação.
- **Disponível** significa serviço de aplicação existente. A rota, os adaptadores de DTO e os testes HTTP continuam pendentes. **Pendente** significa que o serviço, a consulta autorizada ou a infraestrutura também precisa ser implementada. São **36 rotas propostas**, 16 apoiadas por serviços existentes e 20 pendentes de serviço ou infraestrutura.
- Erros comuns incluem 400 para JSON ou parâmetros malformados, 401 para sessão ausente, 403 para CSRF inválido ou política de acesso e 404 para recurso fora do escopo, com a mesma resposta de recurso inexistente. Usar 409 para conflito de estado e 422 para entrada bem formada que viola regra de domínio. A tabela detalha os erros de cada operação.
- Propor `application/problem+json` com `status`, `title`, `code`, `detail` seguro e `fieldErrors` quando aplicável. A camada HTTP deve classificar erros de domínio de forma estável. Exceções inesperadas retornam mensagem genérica e identificador de correlação, sem stack traces, SQL, credenciais ou dados de outra empresa.
- Usar UUID em string, datas contábeis ISO `YYYY-MM-DD`, instantes ISO com offset e valores monetários como strings decimais. Preservar `BigDecimal` no servidor e evitar cálculos monetários com `Number` no navegador. Paginar listagens com limites e ordenação estável. Criações retornam 201 e `Location` quando houver URL canônica de detalhe. Os DTOs de relatórios e abertura existentes servem como base, com adaptação explícita de tipos e campos para HTTP.

| Método | Rota | Serviço ou adaptação | Parâmetros e entrada | Saída | Autorização | Erros relevantes | Serviço |
| --- | --- | --- | --- | --- | --- | --- | --- |
| GET | `/auth/csrf` | Spring Security a configurar | — | CsrfDto | Público, vinculado à sessão | 429 | Pendente |
| POST | `/auth/login` | Autenticação a criar | corpo LoginDto | SessaoDto, 200 | Público, CSRF | 400, 401, 403, 429 | Pendente |
| GET | `/auth/me` | Leitura do principal a expor | — | SessaoDto | Sessão ativa | 401 | Pendente |
| POST | `/auth/logout` | Logout a configurar | — | 204 | Sessão, CSRF | 401, 403 | Pendente |
| GET | `/empresas` | Consulta de empresas por titular a criar | página, tamanho | Página de EmpresaDto | Sessão, somente empresas do principal | 400 | Pendente |
| POST | `/empresas` | Cadastro autorizado a criar | corpo EmpresaEntrada | EmpresaDto, 201 | Titular deriva do principal | 409 CNPJ, 422 dados | Pendente |
| GET | `/empresas/{e}` | Consulta autorizada a criar, com EmpresaRepository | e | EmpresaDto | T | 404 | Pendente |
| PATCH | `/empresas/{e}` | Edição autorizada a criar | e, corpo EmpresaEdicao | EmpresaDto | A | 409 CNPJ, 422 dados | Pendente |
| POST | `/empresas/{e}/arquivamento` | Arquivamento autorizado a criar | e, corpo ArquivamentoEntrada | EmpresaDto | A | 409 estado, 422 motivo | Pendente |
| GET | `{E}/plano-contas` | Consulta autorizada do plano a criar, com ContaContabilRepository | e | PlanoDto | A | 404 plano | Pendente |
| POST | `{E}/plano-contas` | PlanoContasService.provisionar | e | PlanoDto, 201 | A | 409 plano existente ou modelo indisponível | Disponível |
| GET | `{E}/exercicios` | Consulta autorizada a criar | e, página, tamanho | Página de ExercicioDto | A | 400 | Pendente |
| POST | `{E}/exercicios` | Cadastro de exercício a criar | e, corpo ExercicioEntrada | ExercicioDto, 201 | A | 409 ano duplicado, 422 calendário | Pendente |
| GET | `{X}/periodos` | Consulta autorizada a criar, com PeriodoContabilRepository | e, x | lista de PeriodoDto | A, exercício da empresa | 404 exercício | Pendente |
| POST | `{X}/periodos` | Cadastro de período a criar | e, x, corpo PeriodoEntrada | PeriodoDto, 201 | A, exercício da empresa | 409 encerrado ou mês duplicado, 422 calendário | Pendente |
| POST | `{E}/lancamentos` | LancamentoContabilService.criarRascunho | e, corpo RascunhoEntrada | LancamentoDto, 201 | A, exercício e período da empresa | 404 vínculos, 409 encerrado, 422 data ou histórico | Disponível |
| GET | `{E}/lancamentos` | Consulta paginada autorizada a criar | e, exercicioId, periodoId?, status?, inicio?, fim?, página, tamanho | Página de LancamentoResumo | A, filtros no escopo | 404 vínculos, 422 intervalo | Pendente |
| GET | `{L}` | Consulta autorizada a criar, com LancamentoContabilRepository e PartidaContabilRepository | e, l | LancamentoDto com partidas | A, lançamento da empresa | 404 lançamento | Pendente |
| POST | `{L}/partidas` | LancamentoContabilService.adicionarPartida | e, l, corpo PartidaEntrada | PartidaDto, 201 | A, rascunho e conta do plano | 404 IDs, 409 estado, 422 conta ou valor | Disponível |
| DELETE | `{L}/partidas/{p}` | LancamentoContabilService.removerPartida | e, l, p | 204 | A, partida do lançamento | 404 vínculo, 409 estado | Disponível |
| POST | `{L}/confirmacao` | LancamentoContabilService.confirmarLancamento | e, l | LancamentoDto, 200 | A, lançamento da empresa | 404 lançamento, 409 estado, calendário ou continuidade, 422 partidas ou limite temporal | Disponível |
| DELETE | `{L}` | Cancelamento de rascunho manual a criar, com auditoria na mesma transação | e, l | 204 | A, somente rascunho manual da empresa | 404 lançamento, 409 estado ou origem | Pendente |
| POST | `{L}/estornos` | LancamentoContabilService.criarRascunhoEstorno | e, l original, corpo EstornoEntrada | LancamentoDto, 201 | A, original e destino da empresa | 404 vínculos, 409 reserva ou estado, 422 data | Disponível |
| POST | `{E}/estornos/{s}/confirmacao` | LancamentoContabilService.confirmarEstorno | e, s | LancamentoDto, 200 | A, estorno e original da empresa | 404 vínculos, 409 estado ou continuidade, 422 data ou divergência das partidas | Disponível |
| DELETE | `{E}/estornos/{s}` | LancamentoContabilService.cancelarRascunhoEstorno | e, s | 204 | A, estorno da empresa | 404 estorno, 409 estado | Disponível |
| POST | `{X}/saldos-iniciais` | LancamentoContabilService.registrarSaldosIniciais | e, x, corpo AberturaEntrada | AberturaConfirmada, 201 | A, exercício e contas do plano | 404 vínculos, 409 exercício ou abertura existente, 422 valores ou equilíbrio | Disponível |
| GET | `{X}/relatorios/diario` | LivroDiarioService.emitir | e, x, inicio, fim | LivroDiario | A, exercício da empresa | 404 exercício, 422 intervalo | Disponível |
| GET | `{X}/relatorios/razao` | LivroRazaoService.emitir | e, x, contaId, inicio, fim | LivroRazao | A, conta do plano | 404 vínculos, 422 intervalo ou conta sintética | Disponível |
| GET | `{X}/relatorios/balancete` | BalanceteService.emitir | e, x, inicio, fim | Balancete | A, exercício da empresa | 404 exercício, 409 hierarquia, 422 intervalo | Disponível |
| GET | `{X}/relatorios/bp` | BalancoPatrimonialService.emitir | e, x, dataBase | BalancoPatrimonial | A, exercício da empresa | 404 exercício, 409 classificação, 422 data | Disponível |
| GET | `{X}/relatorios/dre` | DreService.emitir | e, x, inicio, fim | Dre | A, exercício da empresa | 404 exercício, 409 classificação, 422 intervalo | Disponível |
| POST | `{X}/periodos/{p}/encerramento` | FechamentoContabilService.encerrarPeriodo | e, x, p | PeriodoEncerrado, 200 | A, período do exercício | 404 vínculos, 409 ordem, rascunhos, dezembro ou estado | Disponível |
| POST | `{X}/encerramento` | FechamentoContabilService.encerrarExercicio, inclui o transporte | e, x | ExercicioEncerrado, 200 | A, origem e sucessor da empresa | 404 exercício, 409 pendências, destino ou estado, 422 classificação ou conta inativa | Disponível |
| GET | `{X}/continuidade` | Projeção de vínculo e lançamentos técnicos a criar | e, x de origem | ContinuidadeDto | A, origem e sucessor da empresa | 404 exercício ou continuidade | Pendente |
| GET | `{E}/auditoria` | Consulta autorizada a criar. Escrita localizada no fechamento já existe | e, inicio?, fim?, acao?, entidade?, página, tamanho | Página de EventoDto | A, eventos da empresa | 400 filtros, 422 intervalo | Pendente |
| GET | `{X}/relatorios/{tipo}/pdf` | Exportador a criar, usando relatórios existentes e consulta de histórico a criar | e, x, tipo, filtros conforme relatório | application/pdf, 200 | A, mesmos escopos do relatório | 404 tipo ou recurso, 409 dados ou PDF integral indisponível, 422 filtros | Pendente |

### DTOs de entrada e saída

| Contrato | Campos preliminares |
| --- | --- |
| `LoginDto`, `CsrfDto`, `SessaoDto` | Entrada com `email` e `senha`. Resposta CSRF com `token` e `headerName`. Sessão com `usuarioId`, `nome` e `email` do principal. |
| `EmpresaEntrada`, `EmpresaEdicao`, `ArquivamentoEntrada`, `EmpresaDto` | Cadastro com `razaoSocial`, `nomeFantasia?`, `cnpj?`. Edição parcial dos mesmos campos. Arquivamento com `motivo`. Saída com os dados da empresa, `id`, `ativa` e `arquivadaEm?`. |
| `PlanoDto` | ID, versão do modelo e contas com ID, pai, código, nome, tipo, natureza, grupo, estado e classificações, quando aplicáveis. |
| `ExercicioEntrada`, `PeriodoEntrada` | Ano do exercício ou mês do período. O servidor deriva as datas do calendário civil adotado na V4. |
| `ExercicioDto`, `PeriodoDto` | ID, ano, mês quando aplicável, datas inicial e final, status e `exercicioOrigemId?` para exercício. O servidor define status e vínculo. |
| `RascunhoEntrada`, `PartidaEntrada` | Rascunho com `exercicioId`, `periodoId`, `data` e `historico`. Partida com `contaId`, `tipo`, `valor` e `historico?`. |
| `LancamentoResumo`, `LancamentoDto`, `PartidaDto` | Cabeçalho com ID, exercício, período, data, número quando confirmado, origem, status, histórico e ID do original se for estorno. O detalhe acrescenta as partidas com ID, conta, tipo, valor e histórico. |
| `EstornoEntrada`, `AberturaEntrada` | Estorno com `exercicioDestinoId`, `periodoDestinoId`, `data` e `historico`. Abertura com `periodoId`, `data`, `historico` e `saldos[]` com conta, tipo e valor. Adaptar para `AberturaContabil` no servidor, usando o principal e os IDs da rota. |
| `ContinuidadeDto`, `EventoDto` | Continuidade com exercícios de origem e destino, data e referências técnicas opcionais. Auditoria com evento, ação, entidade, ID, responsável e instante, com dados anteriores e novos apenas quando autorizados e sanitizados. |
| Relatórios | Reutilizar os records contábeis como base dos DTOs HTTP. Preservar referência e ordem das partidas, saldos assinados, indicadores de equilíbrio e ressalva da migração na DRE. |

O encerramento anual inclui a continuidade na mesma operação. A rota GET é somente consulta do vínculo e referências, inclusive quando o saldo zero dispensou lançamento de abertura. A API não aceitará comandos independentes de transporte ou alteração direta de estado.

A proposta de PDF usa `tipo` entre `historico`, `diario`, `razao`, `balancete`, `bp` e `dre`, com `dataBase` para BP e `inicio` e `fim` para os demais. Para Razão, `contaId` seleciona uma conta. A exportação integral sem `contaId` depende de consulta agregada de todas as contas e paginação de relatório. A emissão do serviço atual é por conta. O histórico completo depende da consulta autorizada de lançamentos. O resultado será um download privado da mesma origem.

Reservar espaços de navegação para estoque, ativos e depreciação. Definir as rotas e DTOs operacionais após validar as regras desses módulos e sua contabilização.

## Autenticação, sessão e autorização

- Adotar Spring Security com sessão de servidor e login por e-mail e senha. Consultar o usuário por e-mail de acordo com a unicidade insensível a maiúsculas prevista na V1. Persistir apenas hash adaptativo, como BCrypt via `PasswordEncoder`, com fator de custo validado no ambiente escolhido. Usuário inativo e credenciais inválidas recebem resposta genérica no login. Prever limitação de tentativas.
- O servidor obtém `usuarioId` do principal em todas as operações. IDs enviados em rota ou corpo representam recursos, sem atribuir identidade ou titularidade. Serviços novos de leitura e mutação devem aplicar o mesmo escopo das operações existentes, inclusive download e auditoria. Nunca serializar a entidade `Usuario` ou o campo `senhaHash`.
- Manter cookie de sessão `HttpOnly`, `Secure` sob HTTPS e `SameSite=Lax`. Rotacionar o identificador ao autenticar, expirar sessões por inatividade e invalidar sessão e cookie no logout. Definir tempo de sessão antes da implantação. Revalidar estado ativo e autorização em operações protegidas.
- Exigir CSRF nas mudanças de estado, inclusive login e logout. Incluir o token nos formulários Thymeleaf e enviá-lo em cabeçalho nas chamadas JavaScript. O cliente REST de mesma origem obtém o token em `GET /auth/csrf` e o renova após login ou logout. Manter CORS sem liberação de origens externas na arquitetura proposta. [Referência de CSRF do Spring Security](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html).
- Provisionar as contas de demonstração por procedimento autorizado, com credenciais fornecidas fora do Git. Configurar segredo de banco e demais credenciais no ambiente de execução. Excluir senhas e tokens de logs, payloads de erro e auditoria. Usar dados fictícios na demonstração.

## Navegação e telas

| Área | Fluxo mínimo |
| --- | --- |
| Login | E-mail e senha, feedback genérico de credenciais e retorno ao contexto após autenticação. |
| Empresas e painel | Listar, selecionar, cadastrar e editar empresas. Mostrar empresa e exercício atuais e atalhos para lançamentos, livros e fechamento. |
| Plano, calendário e abertura | Exibir hierarquia e contas analíticas, provisionar plano padrão, listar e cadastrar exercícios e períodos, informar saldos iniciais com débitos, créditos e confirmação explícita. |
| Lançamentos e partidas | Listar por exercício, período e status. Criar rascunho, editar partidas, conferir totais e confirmar. Exibir a ação de cancelar rascunho manual quando o serviço auditável estiver disponível. |
| Estornos | Selecionar original, data e destino, revisar as partidas invertidas e confirmar ou cancelar o rascunho de estorno. |
| Livros e demonstrações | Formulários de recorte, tabelas com cabeçalhos, totais, saldos e indicadores. Mostrar ressalva de migração na DRE e oferecer PDF quando habilitado. |
| Estoque, ativos e depreciação | Reservar as entradas para cadastro e movimentos de estoque, controle patrimonial de ativos e apropriação de depreciação. A experiência será detalhada com os módulos. |
| Fechamento e continuidade | Mostrar estados e pendências, pedir confirmação explícita do fechamento mensal ou anual e apresentar referências dos lançamentos e exercício sucessor. |
| Histórico de operações | Filtrar auditoria por período, ação e entidade. Disponibilizar o PDF do histórico de lançamentos na área de lançamentos ou relatórios. |

Usar rótulos em português brasileiro, navegação por teclado, foco visível, mensagens associadas a campos e tabelas semânticas com cabeçalhos. Identificar situação e natureza do saldo em texto, inclusive quando houver cor. Mostrar valores em formato brasileiro sem alterar o valor enviado ao servidor. Validar o fluxo com o perfil dos alunos do técnico em administração. Aplicar a identidade oficial e tratar os tokens adicionais como propostas de implementação. [Identidade visual](identidade-visual.md) · [Manual oficial](Manual_Identidade_Visual_LEX.pdf).

## Implantação e demonstração

1. Após a implementação da web, validar o mesmo conteúdo com `.\scripts\test.ps1`, que verifica o destino `lex_test`. Em seguida, empacotar com `.\mvnw.cmd -DskipTests package`, mantendo a execução da suíte no script seguro. Templates e estáticos entram no JAR. A configuração recomendada prescinde de um build Node. Se for escolhido React, gerar o build estático em etapa separada e servir o resultado pelo Spring ou pelo mesmo proxy de HTTPS.
2. Configurar banco PostgreSQL exclusivo para demonstração, persistente e separado de desenvolvimento e testes. Configurar explicitamente `DB_URL`, `DB_USERNAME` e `DB_PASSWORD`, para evitar o fallback local. Desabilitar logs SQL desnecessários no perfil de demonstração. Usar a linha de base PostgreSQL 18.4 que validou a V7.
3. Aplicar e validar V1–V7 no primeiro provisionamento ou atualizar a partir da versão existente. Preservar checksums das migrations aplicadas e manter backup antes do upgrade. Definir credenciais e privilégios da role de migração e da aplicação no ambiente aprovado.
4. Publicar o JAR ou imagem em ambiente autorizado, com uma origem HTTPS, banco em rede privada e armazenamento persistente para seus dados. Configurar proxy e cabeçalhos encaminhados conforme o ambiente. Provisionar dados fictícios e usuário de demonstração. Verificar o link externamente, inclusive login, persistência após reinício, fechamento e download de PDF.
5. Validar também um ambiente local com Java e PostgreSQL como contingência para o ensaio. Docker e Compose são opções de empacotamento a verificar na implantação. A entrega ainda depende de link acessível e manual. [Requisitos acadêmicos](requisitos-academicos.md).

## Ordem recomendada e dependências

| Data | Sequência proposta |
| --- | --- |
| 27/11 | Priorizar estoque PEPS, ativos e depreciação com contabilização, como previsto no cronograma revisado. Assim que o fluxo básico estiver disponível, preparar autenticação, escopo por empresa, DTOs de transporte e erros, e começar a navegação e o fluxo de lançamento sem depender de contratos operacionais ainda indefinidos. |
| 28/11 | Integrar login, empresa, plano, calendário, abertura, rascunhos, partidas e confirmação. Em seguida integrar estorno, relatórios, fechamento, telas operacionais, histórico e PDFs. Preparar implantação inicial e validar o fluxo ponta a ponta no ambiente de demonstração. |

Priorizar um fluxo testável do login à consulta da escrituração. Em 29/11, o cronograma reserva estabilização, implantação definitiva, manual e ensaio. [Decisões e cronograma](decisoes-e-cronograma.md).

Dependem de decisão do desenvolvedor a confirmação da arquitetura de interface, o ambiente e a origem pública do link, a forma de provisionar usuários de demonstração e o mecanismo de geração de PDFs. A falta de consulta autorizada, cancelamento auditável de rascunho manual e emissão integral do Razão são dependências de implementação. As verificações operacionais do fechamento, a conciliação dos saldos de abertura com estoque e ativos e a auditoria geral permanecem pendentes. A ressalva sobre o formato acadêmico da DRE permanece registrada. [Andamento](andamento.md).
