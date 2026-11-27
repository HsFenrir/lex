# LEX — Guia de colaboração frontend

## Objetivo e ponto de partida

Construir uma interface educacional que exponha os fluxos reais do LEX, reutilizando as regras e os cálculos do backend. Consulte o [README](../README.md), [integração web](integracao-web.md), [identidade visual](identidade-visual.md) e [andamento](andamento.md).

O 6E registra duas iniciativas de interface. Das 36 rotas propostas, 16 têm serviço de aplicação e 20 exigem implementação adicional. A camada HTTP está pendente.

| Plano | Responsável | Branch proposta |
|---|---|---|
| A | Colaborador frontend | `feat/frontend-colaborador` |
| B | Desenvolvedor principal, para contingência | `feat/frontend-contingencia` |

Ambas usam o mesmo backend, os mesmos contratos de API, as mesmas regras contábeis e a identidade oficial do LEX. Reutilizar o núcleo de negócio em todos os fluxos. O Plano B tem preferência por Thymeleaf, HTML5, CSS3 e JavaScript, com Spring MVC, API REST e sessão no servidor sob a mesma origem HTTPS, em um JAR. Priorizar cobertura funcional, acessibilidade, integração, estabilidade e prazo.

O colaborador pode desenvolver o Plano A em paralelo ao backend. O desenvolvedor principal só inicia o Plano B após a conclusão e validação integral do backend.

Antes da estabilização final, comparar as versões pela cobertura dos requisitos obrigatórios, pelos fluxos ponta a ponta com dados persistidos, pela acessibilidade, pela estabilidade e pela prontidão para implantação. A versão selecionada será integrada de forma controlada na `main`.

## Identidade visual obrigatória

Seguir o [manual aprovado, versão acadêmica 1.0](Manual_Identidade_Visual_LEX.pdf) e sua [tradução técnica para a interface](identidade-visual.md). Azul-marinho na navegação, creme como fundo principal, dourado como acento, Inter na interface. Utilizar a arte raster original disponível em `docs/assets/lex-logo-principal.png`, sem redesenhar o lettering; copiar para recursos estáticos somente na implementação. A matriz vetorial e o favicon simplificado ainda precisam de preparação e validação. Os tokens funcionais adicionais são propostas, não alterações da marca.

## Divisão de responsabilidades proposta

| Frontend | Backend / integração compartilhada |
|---|---|
| Layout, navegação, fragmentos e estilos | Autenticação e autorização por empresa |
| Formulários, tabelas, filtros e feedback | Controllers, DTOs e contratos HTTP |
| Estados de interface e acessibilidade | Cálculos, persistência, numeração e transações |
| Protótipos com fixtures fictícias | Testes de regras e isolamento |
| Testes de navegação e responsividade | Revisão de contratos e testes ponta a ponta em conjunto |

## Ordem de implementação

| Prioridade | Tela / fluxo | Critério de entrega |
|---|---|---|
| P0 | Layout base, login e seleção de empresa | Contexto visível, navegação por teclado, acesso real quando segurança estiver pronta |
| P0 | Plano de contas, exercícios e períodos | Seleção válida, identificação do período aberto/encerrado |
| P0 | Lançamento, partidas, confirmação e estorno | Rascunho persistido, totais, confirmação real e retorno do número; estorno referenciado |
| P0 | Saldos iniciais | Entrada por conta, natureza, valor e validação do equilíbrio |
| P0 | Diário, Razão, Balancete, BP e DRE | Filtros, totais, estados vazios, dados reais e exportação quando disponível |
| P1 | Fechamento | Exibir pendências, confirmar ação e apresentar referências do encerramento |
| P1 | Estoque, ativos e depreciação | Integrar contratos quando os módulos estiverem disponíveis |
| P1 | Histórico e PDFs | Referências, filtros, acesso autorizado e documentos completos |

P0/P1 definem ordem de trabalho, não dispensam requisitos acadêmicos. Começar pelo layout e componentes compartilhados, sem aguardar todos os serviços operacionais.

## Estrutura proposta para Thymeleaf

```text
src/main/resources/
├── templates/
│   ├── fragments/       # cabeçalho, navegação, mensagens
│   ├── auth/
│   ├── empresas/
│   ├── contabil/
│   ├── relatorios/
│   └── operacoes/
└── static/
    ├── css/             # tokens e componentes compartilhados
    ├── js/              # interações, sem regras contábeis autoritativas
    └── img/             # marca e recursos aprovados
```

Os diretórios locais `templates/` e `static/` estão vazios. A estrutura interna acima é proposta. Controllers e serviços permanecem em Java. Compartilhar fragmentos e estilos e alinhar alterações de endpoints e DTOs com o responsável.

## Integração e segurança

- O servidor obtém a identidade pelo principal autenticado; `usuarioId` do navegador não comprova identidade. Toda consulta e alteração depende de autorização efetiva por empresa e recurso.
- Sessão no servidor e CSRF são propostas do 6D. A interface deve seguir o mecanismo real de CSRF para formulários e chamadas REST; não desabilitar proteção para resolver problemas de integração.
- Dados monetários devem preservar exatidão; alinhar serialização decimal com os DTOs efetivos. Não converter valores para ponto flutuante e usá-los como cálculo autoritativo. O servidor revalida toda entrada.
- Mostrar erros de validação úteis e tratar sessão expirada, acesso negado, recurso ausente e conflitos de estado. Não expor SQL, stack traces ou dados de outras empresas.
- Mostrar estados de carregamento e impedir envios duplicados enquanto a solicitação estiver pendente, sem substituir garantias transacionais do backend.
- Não gravar senhas, tokens ou informações contábeis reais em fixtures, localStorage ou arquivos versionados.

## Fluxo de Git

Partir da `main` atualizada quando o trabalho de interface for iniciado. Coordenar alterações em `pom.xml`, configuração de segurança, DTOs e controllers. Manter commits focados e revisar a integração com o responsável. Não executar comandos destrutivos, reescrever a main nem aplicar migrations em bancos compartilhados.

## Definição de pronto para cada tela

- Layout responsivo, coerente com a identidade oficial e com as extensões técnicas validadas para a interface.
- Operação por teclado, foco e labels adequados; mensagens de erro e estados vazios.
- Dados de teste fictícios identificados enquanto não houver API.
- Contrato real integrado e testado para que a tela seja considerada funcional.
- Autorização validada no servidor; nenhum dado de outra empresa exposto.
- Operações que alteram dados respeitam estados contábeis, períodos, abertura e estorno.
- Revisão do diff e testes relevantes concluídos; pendências documentadas.

## Decisões ainda abertas

Arquitetura do Plano A, seleção da versão definitiva, validação das extensões de interface da identidade visual, hospedagem, endereço público, mecanismo de PDF e provisionamento de usuários de demonstração. O cancelamento auditável de rascunhos manuais, Razão integral, auditoria geral e integração operacional do fechamento ainda exigem trabalho de backend. Identificar dados simulados como fixtures.
