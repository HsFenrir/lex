# AGENTS.md — Sistema Contábil LEX

## 1. Objetivo do projeto

Desenvolver um sistema contábil funcional, atendendo integralmente aos requisitos acadêmicos definidos pelo professor, às decisões arquiteturais aprovadas e ao cronograma oficial do projeto LEX.

Priorizar integridade contábil, consistência dos dados, segurança, qualidade técnica e cumprimento do prazo.

## 2. Diretrizes obrigatórias de trabalho

- Responder e apresentar explicações técnicas em português brasileiro.
- Analisar criticamente as solicitações e identificar inconsistências, riscos e premissas incorretas.
- Não presumir informações que possam ser verificadas diretamente no código ou na documentação.
- Consultar os arquivos existentes antes de solicitar informações ao desenvolvedor.
- Não solicitar novamente conteúdos disponíveis no repositório.
- Verificar a implementação atual antes de propor qualquer alteração.
- Identificar divergências entre código, banco de dados, testes e documentação.
- Preservar decisões anteriormente aprovadas.
- Informar limitações, dúvidas relevantes e decisões ainda não autorizadas.
- Não declarar uma tarefa concluída sem evidências suficientes.

## 3. Documentação do projeto

Consultar o documento Decisões e Cronograma — LEX e os documentos de requisitos acadêmicos antes de implementar funcionalidades relacionadas.

Caso esses documentos não estejam disponíveis no repositório, informar a limitação explicitamente.

Não modificar requisitos, decisões arquiteturais ou cronograma sem autorização do desenvolvedor.

## 4. Arquitetura e tecnologias

O backend utiliza Java, Spring Boot, Spring Data JPA, Hibernate, PostgreSQL, Flyway e Maven.

Verificar as versões efetivamente utilizadas no pom.xml e nos demais arquivos de configuração.

Respeitar a estrutura de pacotes, convenções, padrões de nomenclatura e responsabilidades das camadas existentes.

Não introduzir dependências, frameworks ou alterações arquiteturais desnecessárias.

Manter consistência entre entidades JPA, repositories, services, migrations e testes.

## 5. Regras de integridade contábil

- Garantir isolamento dos dados entre empresas.
- Validar a associação entre empresa, exercício contábil, período contábil, plano de contas e contas contábeis.
- Respeitar as chaves estrangeiras compostas e restrições existentes no PostgreSQL.
- Utilizar BigDecimal para valores monetários.
- Não utilizar float ou double para cálculos contábeis.
- Permitir partidas somente em contas analíticas e ativas.
- Exigir valores positivos nas partidas.
- Respeitar a precisão monetária definida no banco de dados.
- Exigir equilíbrio entre débitos e créditos na confirmação dos lançamentos.
- Exigir pelo menos duas partidas para confirmar um lançamento.
- Validar exercício e período contábil abertos na confirmação.
- Garantir que a data do lançamento pertença ao período informado.
- Não consumir numeração contábil durante a criação de rascunhos.
- Atribuir numeração somente na confirmação, atomicamente e com proteção contra concorrência.
- Preservar a imutabilidade dos lançamentos confirmados e estornados, respeitando as transições de estado autorizadas.
- Efetuar correções de lançamentos confirmados por meio de estorno.
- Inverter débitos e créditos na criação do estorno.
- Permitir estorno em exercício ou período posterior, respeitando as restrições aplicáveis.
- Preservar a reserva do lançamento original enquanto existir um rascunho de estorno associado.
- Não permitir múltiplos estornos para o mesmo lançamento original.
- Garantir consistência transacional entre a confirmação do estorno e a atualização do lançamento original.

## 6. Banco de dados e migrations

- Consultar as migrations Flyway antes de alterar entidades ou regras relacionadas à persistência.
- Não modificar migrations já aplicadas sem autorização explícita.
- Implementar novas alterações estruturais mediante migrations versionadas.
- Não remover constraints, triggers, índices ou outras garantias de integridade para contornar erros da aplicação.
- Analisar a causa raiz de incompatibilidades entre Hibernate e PostgreSQL.
- Preservar a integridade referencial e o isolamento entre empresas.
- Não executar operações destrutivas em bancos de dados sem autorização.
- Executar testes somente em ambientes de teste devidamente identificados.

## 7. Processo de implementação

Antes de modificar arquivos:

1. Identificar o objetivo da tarefa.
2. Consultar os requisitos e decisões aplicáveis.
3. Inspecionar os arquivos existentes e suas dependências.
4. Verificar o estado atual do Git.
5. Identificar riscos, inconsistências e impactos.
6. Apresentar um plano objetivo de implementação.

Durante a implementação:

- Realizar alterações incrementais e restritas ao escopo autorizado.
- Preservar funcionalidades existentes.
- Não sobrescrever alterações do desenvolvedor.
- Não duplicar regras de negócio de maneira contraditória.
- Utilizar transações adequadas às operações contábeis.
- Considerar condições de corrida e concorrência.
- Implementar tratamento de erros coerente com a arquitetura existente.

Após a implementação:

- Revisar todas as alterações.
- Executar testes pertinentes.
- Verificar regressões.
- Apresentar os arquivos modificados e os resultados obtidos.
- Informar problemas pendentes e riscos identificados.
- Indicar quando a etapa estiver tecnicamente apta para commit.

## 8. Testes e critérios de conclusão

Utilizar o Maven Wrapper, quando disponível, ou o Maven configurado no ambiente.

Executar clean test nas etapas relevantes.

Implementar testes unitários e de integração conforme a natureza da funcionalidade.

Considerar cenários válidos, inválidos, isolamento entre empresas, integridade referencial, rollback transacional e concorrência.

Não alterar ou desabilitar testes apenas para obter sucesso na compilação.

Não afirmar que testes foram aprovados quando não foram efetivamente executados.

Informar quantidade de testes executados, falhas, erros e testes ignorados.

Uma implementação somente deve ser considerada concluída quando atender aos requisitos, estiver consistente com o banco de dados e possuir validação suficiente.

## 9. Git e controle de versão

- Preservar alterações existentes no diretório de trabalho.
- Não executar commits, pushes, pulls, merges ou rebases sem autorização.
- Não executar reset destrutivo, force push ou remoção de arquivos não rastreados sem autorização explícita.
- Não incluir credenciais, senhas, tokens ou segredos no versionamento.
- Não expor informações sensíveis em respostas ou logs.
- Manter commits pequenos, coerentes e rastreáveis.
- Propor mensagens de commit compatíveis com o histórico do projeto.

## 10. Autonomia e autorização

O agente está autorizado a analisar o código e identificar problemas relacionados à tarefa solicitada.

A implementação deve respeitar o escopo autorizado pelo desenvolvedor.

Alterações arquiteturais relevantes, mudanças de requisitos, operações destrutivas, modificações em produção e ações que comprometam o histórico Git exigem autorização explícita.

Em caso de conflito entre documentação, código existente e solicitação atual, apresentar a divergência antes de tomar decisões irreversíveis.

A prioridade é entregar um sistema funcional, consistente, verificável e aderente aos requisitos do projeto LEX.