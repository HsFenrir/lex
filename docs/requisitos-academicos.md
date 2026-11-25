# LEX — Requisitos acadêmicos e critérios de aceitação

Consolidação inicial: 25/11/2026. **Status: parcial; requer conferência com o enunciado integral do professor.**

## 1. Autoridade das fontes

- O documento Decisões e Cronograma 23-30 nov 2026.md, registrado em 22/11, estabelece o planejamento aprovado do LEX. Consultar decisoes-e-cronograma.md.
- Livro da turma 2026.pdf é referência didática de contabilidade financeira, não um enunciado comprovado de entrega de software.
- As exigências específicas do professor descritas em outros chats não foram integralmente recuperadas nesta consolidação. Não declarar cobertura integral nem atribuir ao professor requisitos não comprovados.
- Em conflito ou lacuna, solicitar decisão do desenvolvedor antes de ampliar ou reduzir o escopo. Não confundir critérios de engenharia com exigências expressas do professor.

## 2. Escopo funcional estabelecido pelo planejamento do projeto

| ID | Funcionalidade | Critério de aceitação |
|---|---|---|
| F01 | Acesso e contexto de empresa | Fluxo de acesso funcional; operações limitadas à empresa autorizada. |
| F02 | Plano de contas | Plano e contas da empresa disponíveis; associação e hierarquia consistentes; contas sintéticas não recebem partidas. |
| F03 | Exercícios e períodos | Selecionar exercício e período da empresa; validar coerência de datas e situação de abertura. |
| F04 | Lançamentos em rascunho | Criar cabeçalho com empresa, exercício, período, plano, data, histórico e origem; sem numeração definitiva. |
| F05 | Partidas | Incluir/remover partidas no rascunho; conta analítica ativa do mesmo plano, débito/crédito e valor positivo válido. |
| F06 | Confirmação | Ao menos duas partidas; débitos iguais a créditos; exercício e período abertos; empresa ativa; numeração atômica; transação consistente. |
| F07 | Estorno | Original confirmado; reservar original ao criar rascunho; inverter partidas; confirmar e atualizar original atomicamente; impedir duplicidade. |
| F08 | API | Contratos de entrada/saída, validação, tratamento de erros e autorização dos fluxos essenciais. |
| F09 | Interface | Navegação e telas integradas à API para os fluxos essenciais; não depender de SQL manual para demonstrá-los. |
| F10 | Demonstração | Ambiente inicializável, dados de exemplo e execução ponta a ponta verificável até a apresentação de 30/11. |

A tabela é uma decomposição técnica do planejamento, não uma transcrição literal do enunciado acadêmico. A cobertura de cada item deve ser comprovada por implementação e teste.

## 3. Fundamentação contábil — material da disciplina

Referência: Livro da turma 2026.pdf (paginação impressa). Usar a fonte para conferir conceitos, classificações e exemplos; não interpretar a presença de um assunto como obrigação de implementar um módulo.

| Seção / páginas iniciais | Conceito e aplicação ao LEX |
|---|---|
| 1.2–1.3, p. 8 e 12 | Contabilidade financeira e gerencial; qualidade e verificabilidade das informações. Fundamentam consistência e rastreabilidade. |
| 2.1, p. 15 | Contas e plano de contas organizam os registros patrimoniais. |
| 2.2, p. 17 | Débitos e créditos conforme a natureza contábil. Não confundir débito com despesa nem crédito com receita em todos os casos. |
| 2.3, p. 19 | Balancete de verificação: identificação da data e contas, respectivos saldos e igualdade dos totais devedores e credores. |
| 2.4, p. 21 | Balanço patrimonial: classificação de ativo, passivo e patrimônio líquido. |
| 3.1, p. 30 | Livro Diário: registros cronológicos com data, histórico, contas debitadas/creditadas e valor. |
| 3.2, p. 31 | Livro Razão: movimentos por conta, saldo inicial, débitos, créditos e saldo final. |
| 3.3, p. 34 | Razonetes: representação dos movimentos a débito e crédito por conta. |
| 4.1–4.2, p. 41 e 46 | Receitas, despesas e venda de mercadorias; observar competência, não apenas movimentação de caixa. |
| 4.3, p. 48 | DRE: composição do resultado por receitas, deduções, custos e despesas. |
| 4.4–4.7, p. 52–62 | Depreciação, amortização, exaustão e PECLD: referências de fatos contábeis; automação específica depende de escopo confirmado. |
| 5, p. 68 | Fluxo de caixa: referência didática; não confundir caixa com resultado por competência. |

## 4. Relatórios e funcionalidades com obrigatoriedade acadêmica a confirmar

O material didático aborda Livro Diário, Livro Razão, razonetes, balancete, balanço patrimonial, DRE e fluxo de caixa. O cronograma recuperado não especifica expressamente quais desses relatórios devem estar implementados na entrega. Verificar o enunciado original e as decisões anteriores antes de classificá-los como obrigatórios, opcionais ou fora do escopo. Se confirmados, planejar a entrega dentro do prazo e registrar decisão.

Critérios sugeridos para os relatórios, **se aprovados no escopo**: cálculo a partir de partidas de lançamentos efetivados (incluindo o original estornado e seu estorno confirmado nos períodos correspondentes); excluir rascunhos; respeitar empresa e corte temporal; evitar dupla contagem por agregação de contas sintéticas; reconciliar saldos e totais com o Razão e o Diário. A forma de encerramento e apresentação do resultado deve ser validada antes de implementar BP/DRE.

## 5. Critérios técnicos transversais

- Segurança e isolamento entre empresas, inclusive casos negativos e acesso a IDs de outra empresa.
- Integridade referencial e transacional: nenhuma confirmação parcial, nenhuma numeração duplicada, nenhum estorno duplicado.
- BigDecimal, NUMERIC(19,2), sem perda monetária silenciosa.
- Rastreabilidade, preservação do histórico, imutabilidade após confirmação e correção por estorno.
- Testes de integração no PostgreSQL real e segregado; Flyway validado; erros esperados verificados pela causa correta.
- API e frontend integrados; teste ponta a ponta e dados de apresentação.
- Credenciais fora do Git; operações de teste restritas ao banco autorizado.

## 6. Limites e itens a confirmar com a fonte primária

- Enunciado integral do trabalho, rubrica de avaliação, requisitos de entrega e formato da apresentação.
- Relatórios contábeis efetivamente obrigatórios, filtros, exportações, apresentação gráfica e exemplos exigidos.
- Necessidade de módulos específicos de estoque, imobilizado e depreciação. O planejamento determina não priorizá-los sem exigência explícita.
- Requisitos de autenticação, papéis, tecnologias obrigatórias, implantação e documentação formal, se houver.

Não assumir que esses itens são dispensáveis nem inventar requisitos. Atualizar este arquivo quando a especificação original for localizada; registrar a origem e a decisão. O objetivo é a entrega de um sistema funcional aderente às exigências reais do professor, não apenas uma suíte de testes verde.
