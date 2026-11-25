# LEX — Requisitos acadêmicos e critérios de aceitação

Atualização: 25/11/2026, com o enunciado, as orientações de aula e as decisões complementares fornecidos pelo desenvolvedor.

## 1. Autoridade das fontes

- O documento Decisões e Cronograma 23-30 nov 2026.md, registrado em 22/11, estabelece o planejamento aprovado do LEX. Consultar decisoes-e-cronograma.md.
- Livro da turma 2026.pdf é referência didática de contabilidade financeira, não um enunciado comprovado de entrega de software.
- O enunciado original e as orientações complementares de aula foram fornecidos pelo desenvolvedor em 25/11. As decisões de implementação do desenvolvedor estão registradas separadamente na seção 4.
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
| F11 | Saldos iniciais e fechamento | Registrar saldos iniciais, com possibilidade de entrada por balancete, e automatizar o fechamento com o mínimo de entradas manuais. |
| F12 | Livros e demonstrações | Disponibilizar Diário, Razão, Balancete, BP e DRE, além de PDF do histórico de lançamentos e dos livros e demonstrações indicados em aula. |
| F13 | Estoque | Controlar entradas e saídas relacionadas às vendas pelo método PEPS e integrar a contabilização ao núcleo de lançamentos. |
| F14 | Ativos e depreciação | Controlar ativos com valor, vida útil e valor residual, contabilizar os fatos e apropriar mensalmente a depreciação linear pelo núcleo de lançamentos. |
| F15 | Auditoria | Identificar individualmente os lançamentos e manter histórico auditável das modificações, com os campos definidos na seção 4. |
| F16 | Entrega | Disponibilizar o sistema por link e entregar o manual. |

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

## 4. Enunciado, orientações de aula e decisões complementares

### Enunciado original informado pelo desenvolvedor

Entregar o link do sistema contábil e o manual. O sistema será utilizado para a abertura empresarial dos alunos do técnico em administração e deve conter:

- Livro Diário.
- Livro Razão.
- Balance.
- Balanço Patrimonial.
- DRE.
- Controle de estoque, com contabilização.
- Controle de ativos no geral, com contabilização.
- Controle de depreciação.

O termo “Balance” é mantido como fornecido no enunciado. O planejamento usa “Balancete”, conforme as orientações de aula.

### Orientações complementares de aula

- Disponibilizar um sistema acessível, com login, histórico de operações e persistência das empresas e valores.
- Permitir entrada de saldos iniciais, inclusive via balancete, e identificar cada lançamento por registro ou referência individual.
- Manter histórico de todas as modificações para garantir auditabilidade.
- Considerar o sistema do Moodle como possível referência inicial.
- Gerar PDF do histórico de lançamentos, dos principais livros, do balancete, do balanço patrimonial e da DRE.
- Permitir automatizar o fechamento e considerar o modelo de DRE a ser disponibilizado pelo professor.
- Controlar entradas e saídas de estoque relacionadas às vendas e registrar valor, vida útil e valor residual dos ativos.

### Decisões complementares do desenvolvedor

- Usar o método PEPS no estoque e depreciação linear com apropriação mensal, considerando vida útil e valor residual.
- Adotar um modelo de DRE adequado ao projeto. A orientação acadêmica menciona um modelo específico do professor. Sua disponibilidade e eventuais diferenças de formato continuam pendentes de conferência.
- Automatizar o fechamento contábil, minimizando entradas manuais.
- Registrar na auditoria usuário, empresa, data, operação, entidade, identificador e valores anteriores e posteriores quando aplicável.
- Integrar estoque, ativos e depreciação ao núcleo de lançamentos contábeis. Todos os módulos obrigatórios compõem a entrega.
- Dispensar o sistema do Moodle como referência para a implementação.
- Manter DFC, amortização e exaustão fora do escopo confirmado, salvo nova exigência documentada. Razonetes permanecem como referência didática.

Os relatórios devem partir de partidas efetivadas, considerando o original estornado e o estorno confirmado nos respectivos períodos, excluindo rascunhos e respeitando empresa e corte temporal. Evitar dupla contagem na agregação de contas sintéticas e reconciliar saldos e totais com Razão e Diário. Validar a forma de encerramento e apresentação do resultado antes de implementar BP e DRE.

## 5. Critérios técnicos transversais

- Segurança e isolamento entre empresas, inclusive casos negativos e acesso a IDs de outra empresa.
- Integridade referencial e transacional: nenhuma confirmação parcial, nenhuma numeração duplicada, nenhum estorno duplicado.
- BigDecimal, NUMERIC(19,2), sem perda monetária silenciosa.
- Rastreabilidade, preservação do histórico, imutabilidade após confirmação e correção por estorno.
- Testes de integração no PostgreSQL real e segregado; Flyway validado; erros esperados verificados pela causa correta.
- API e frontend integrados; teste ponta a ponta e dados de apresentação.
- Credenciais fora do Git; operações de teste restritas ao banco autorizado.

## 6. Limites e itens a confirmar com a fonte primária

- Rubrica de avaliação, formato da apresentação e eventuais instruções adicionais para o link, o manual e a implantação.
- Modelo de DRE do professor e eventuais filtros, exemplos, formatos gráficos ou exportações adicionais aos PDFs já indicados.
- Detalhes de papéis, tecnologias exigidas e critérios específicos de acessibilidade, se houver.

Não assumir que esses itens são dispensáveis nem inventar requisitos. Atualizar este arquivo quando a especificação original for localizada; registrar a origem e a decisão. O objetivo é a entrega de um sistema funcional aderente às exigências reais do professor, não apenas uma suíte de testes verde.
