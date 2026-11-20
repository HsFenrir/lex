
-- =========================================================
-- LEX - V3
-- Carga do modelo padrão de plano de contas - versão 1
-- PostgreSQL 18
-- =========================================================

-- Tabela temporária para organizar a carga hierárquica.
CREATE TEMP TABLE tmp_contas_padrao (
    codigo VARCHAR(30) PRIMARY KEY,
    nome VARCHAR(200) NOT NULL,
    grupo VARCHAR(20) NOT NULL,
    natureza VARCHAR(10) NOT NULL,
    tipo VARCHAR(12) NOT NULL,
    codigo_pai VARCHAR(30),
    conta_redutora BOOLEAN NOT NULL DEFAULT FALSE,
    codigo_conta_reduzida VARCHAR(30),
    classificacao_bp VARCHAR(30),
    classificacao_dre VARCHAR(30)
) ON COMMIT DROP;


-- =========================================================
-- 1. ATIVO
-- =========================================================

INSERT INTO tmp_contas_padrao VALUES
                                  ('1', 'ATIVO', 'ATIVO', 'DEVEDORA', 'SINTETICA', NULL, FALSE, NULL, NULL, NULL),

                                  ('1.1', 'Ativo Circulante', 'ATIVO', 'DEVEDORA', 'SINTETICA', '1', FALSE, NULL, 'ATIVO_CIRCULANTE', NULL),

                                  ('1.1.01', 'Disponibilidades', 'ATIVO', 'DEVEDORA', 'SINTETICA', '1.1', FALSE, NULL, 'ATIVO_CIRCULANTE', NULL),
                                  ('1.1.01.001', 'Caixa Geral', 'ATIVO', 'DEVEDORA', 'ANALITICA', '1.1.01', FALSE, NULL, 'ATIVO_CIRCULANTE', NULL),
                                  ('1.1.01.002', 'Bancos Conta Movimento', 'ATIVO', 'DEVEDORA', 'ANALITICA', '1.1.01', FALSE, NULL, 'ATIVO_CIRCULANTE', NULL),
                                  ('1.1.01.003', 'Aplicações Financeiras de Curto Prazo', 'ATIVO', 'DEVEDORA', 'ANALITICA', '1.1.01', FALSE, NULL, 'ATIVO_CIRCULANTE', NULL),

                                  ('1.1.02', 'Contas a Receber', 'ATIVO', 'DEVEDORA', 'SINTETICA', '1.1', FALSE, NULL, 'ATIVO_CIRCULANTE', NULL),
                                  ('1.1.02.001', 'Clientes', 'ATIVO', 'DEVEDORA', 'ANALITICA', '1.1.02', FALSE, NULL, 'ATIVO_CIRCULANTE', NULL),
                                  ('1.1.02.002', 'Títulos a Receber', 'ATIVO', 'DEVEDORA', 'ANALITICA', '1.1.02', FALSE, NULL, 'ATIVO_CIRCULANTE', NULL),
                                  ('1.1.02.003', '(-) Perdas Estimadas em Créditos de Liquidação Duvidosa', 'ATIVO', 'CREDORA', 'ANALITICA', '1.1.02', TRUE, '1.1.02.001', 'ATIVO_CIRCULANTE', NULL),

                                  ('1.1.03', 'Estoques', 'ATIVO', 'DEVEDORA', 'SINTETICA', '1.1', FALSE, NULL, 'ATIVO_CIRCULANTE', NULL),
                                  ('1.1.03.001', 'Mercadorias para Revenda', 'ATIVO', 'DEVEDORA', 'ANALITICA', '1.1.03', FALSE, NULL, 'ATIVO_CIRCULANTE', NULL),
                                  ('1.1.03.002', 'Produtos Acabados', 'ATIVO', 'DEVEDORA', 'ANALITICA', '1.1.03', FALSE, NULL, 'ATIVO_CIRCULANTE', NULL),
                                  ('1.1.03.003', 'Produtos em Elaboração', 'ATIVO', 'DEVEDORA', 'ANALITICA', '1.1.03', FALSE, NULL, 'ATIVO_CIRCULANTE', NULL),
                                  ('1.1.03.004', 'Matérias-Primas', 'ATIVO', 'DEVEDORA', 'ANALITICA', '1.1.03', FALSE, NULL, 'ATIVO_CIRCULANTE', NULL),
                                  ('1.1.03.005', 'Materiais Secundários e de Consumo', 'ATIVO', 'DEVEDORA', 'ANALITICA', '1.1.03', FALSE, NULL, 'ATIVO_CIRCULANTE', NULL),

                                  ('1.1.04', 'Tributos a Recuperar', 'ATIVO', 'DEVEDORA', 'SINTETICA', '1.1', FALSE, NULL, 'ATIVO_CIRCULANTE', NULL),
                                  ('1.1.04.001', 'ICMS a Recuperar', 'ATIVO', 'DEVEDORA', 'ANALITICA', '1.1.04', FALSE, NULL, 'ATIVO_CIRCULANTE', NULL),
                                  ('1.1.04.002', 'IPI a Recuperar', 'ATIVO', 'DEVEDORA', 'ANALITICA', '1.1.04', FALSE, NULL, 'ATIVO_CIRCULANTE', NULL),
                                  ('1.1.04.003', 'PIS a Recuperar', 'ATIVO', 'DEVEDORA', 'ANALITICA', '1.1.04', FALSE, NULL, 'ATIVO_CIRCULANTE', NULL),
                                  ('1.1.04.004', 'COFINS a Recuperar', 'ATIVO', 'DEVEDORA', 'ANALITICA', '1.1.04', FALSE, NULL, 'ATIVO_CIRCULANTE', NULL),
                                  ('1.1.04.005', 'ISS a Recuperar', 'ATIVO', 'DEVEDORA', 'ANALITICA', '1.1.04', FALSE, NULL, 'ATIVO_CIRCULANTE', NULL),

                                  ('1.1.05', 'Adiantamentos e Despesas Antecipadas', 'ATIVO', 'DEVEDORA', 'SINTETICA', '1.1', FALSE, NULL, 'ATIVO_CIRCULANTE', NULL),
                                  ('1.1.05.001', 'Adiantamentos a Fornecedores', 'ATIVO', 'DEVEDORA', 'ANALITICA', '1.1.05', FALSE, NULL, 'ATIVO_CIRCULANTE', NULL),
                                  ('1.1.05.002', 'Adiantamentos a Empregados', 'ATIVO', 'DEVEDORA', 'ANALITICA', '1.1.05', FALSE, NULL, 'ATIVO_CIRCULANTE', NULL),
                                  ('1.1.05.003', 'Despesas Antecipadas', 'ATIVO', 'DEVEDORA', 'ANALITICA', '1.1.05', FALSE, NULL, 'ATIVO_CIRCULANTE', NULL),

                                  ('1.2', 'Ativo Não Circulante', 'ATIVO', 'DEVEDORA', 'SINTETICA', '1', FALSE, NULL, 'ATIVO_NAO_CIRCULANTE', NULL),

                                  ('1.2.01', 'Realizável a Longo Prazo', 'ATIVO', 'DEVEDORA', 'SINTETICA', '1.2', FALSE, NULL, 'ATIVO_NAO_CIRCULANTE', NULL),
                                  ('1.2.01.001', 'Depósitos Judiciais de Longo Prazo', 'ATIVO', 'DEVEDORA', 'ANALITICA', '1.2.01', FALSE, NULL, 'ATIVO_NAO_CIRCULANTE', NULL),
                                  ('1.2.01.002', 'Créditos de Longo Prazo', 'ATIVO', 'DEVEDORA', 'ANALITICA', '1.2.01', FALSE, NULL, 'ATIVO_NAO_CIRCULANTE', NULL),

                                  ('1.2.02', 'Investimentos', 'ATIVO', 'DEVEDORA', 'SINTETICA', '1.2', FALSE, NULL, 'ATIVO_NAO_CIRCULANTE', NULL),
                                  ('1.2.02.001', 'Participações Societárias', 'ATIVO', 'DEVEDORA', 'ANALITICA', '1.2.02', FALSE, NULL, 'ATIVO_NAO_CIRCULANTE', NULL),

                                  ('1.2.03', 'Imobilizado', 'ATIVO', 'DEVEDORA', 'SINTETICA', '1.2', FALSE, NULL, 'ATIVO_NAO_CIRCULANTE', NULL),
                                  ('1.2.03.001', 'Terrenos', 'ATIVO', 'DEVEDORA', 'ANALITICA', '1.2.03', FALSE, NULL, 'ATIVO_NAO_CIRCULANTE', NULL),
                                  ('1.2.03.002', 'Edificações', 'ATIVO', 'DEVEDORA', 'ANALITICA', '1.2.03', FALSE, NULL, 'ATIVO_NAO_CIRCULANTE', NULL),
                                  ('1.2.03.003', 'Máquinas e Equipamentos', 'ATIVO', 'DEVEDORA', 'ANALITICA', '1.2.03', FALSE, NULL, 'ATIVO_NAO_CIRCULANTE', NULL),
                                  ('1.2.03.004', 'Móveis e Utensílios', 'ATIVO', 'DEVEDORA', 'ANALITICA', '1.2.03', FALSE, NULL, 'ATIVO_NAO_CIRCULANTE', NULL),
                                  ('1.2.03.005', 'Veículos', 'ATIVO', 'DEVEDORA', 'ANALITICA', '1.2.03', FALSE, NULL, 'ATIVO_NAO_CIRCULANTE', NULL),
                                  ('1.2.03.006', 'Equipamentos de Informática', 'ATIVO', 'DEVEDORA', 'ANALITICA', '1.2.03', FALSE, NULL, 'ATIVO_NAO_CIRCULANTE', NULL),
                                  ('1.2.03.007', 'Instalações', 'ATIVO', 'DEVEDORA', 'ANALITICA', '1.2.03', FALSE, NULL, 'ATIVO_NAO_CIRCULANTE', NULL),
                                  ('1.2.03.101', '(-) Depreciação Acumulada de Edificações', 'ATIVO', 'CREDORA', 'ANALITICA', '1.2.03', TRUE, '1.2.03.002', 'ATIVO_NAO_CIRCULANTE', NULL),
                                  ('1.2.03.102', '(-) Depreciação Acumulada de Máquinas e Equipamentos', 'ATIVO', 'CREDORA', 'ANALITICA', '1.2.03', TRUE, '1.2.03.003', 'ATIVO_NAO_CIRCULANTE', NULL),
                                  ('1.2.03.103', '(-) Depreciação Acumulada de Móveis e Utensílios', 'ATIVO', 'CREDORA', 'ANALITICA', '1.2.03', TRUE, '1.2.03.004', 'ATIVO_NAO_CIRCULANTE', NULL),
                                  ('1.2.03.104', '(-) Depreciação Acumulada de Veículos', 'ATIVO', 'CREDORA', 'ANALITICA', '1.2.03', TRUE, '1.2.03.005', 'ATIVO_NAO_CIRCULANTE', NULL),
                                  ('1.2.03.105', '(-) Depreciação Acumulada de Equipamentos de Informática', 'ATIVO', 'CREDORA', 'ANALITICA', '1.2.03', TRUE, '1.2.03.006', 'ATIVO_NAO_CIRCULANTE', NULL),
                                  ('1.2.03.106', '(-) Depreciação Acumulada de Instalações', 'ATIVO', 'CREDORA', 'ANALITICA', '1.2.03', TRUE, '1.2.03.007', 'ATIVO_NAO_CIRCULANTE', NULL),

                                  ('1.2.04', 'Intangível', 'ATIVO', 'DEVEDORA', 'SINTETICA', '1.2', FALSE, NULL, 'ATIVO_NAO_CIRCULANTE', NULL),
                                  ('1.2.04.001', 'Softwares', 'ATIVO', 'DEVEDORA', 'ANALITICA', '1.2.04', FALSE, NULL, 'ATIVO_NAO_CIRCULANTE', NULL),
                                  ('1.2.04.002', 'Marcas, Patentes e Direitos', 'ATIVO', 'DEVEDORA', 'ANALITICA', '1.2.04', FALSE, NULL, 'ATIVO_NAO_CIRCULANTE', NULL),
                                  ('1.2.04.101', '(-) Amortização Acumulada', 'ATIVO', 'CREDORA', 'ANALITICA', '1.2.04', TRUE, '1.2.04.001', 'ATIVO_NAO_CIRCULANTE', NULL);


-- =========================================================
-- 2. PASSIVO
-- =========================================================

INSERT INTO tmp_contas_padrao VALUES
                                  ('2', 'PASSIVO', 'PASSIVO', 'CREDORA', 'SINTETICA', NULL, FALSE, NULL, NULL, NULL),

                                  ('2.1', 'Passivo Circulante', 'PASSIVO', 'CREDORA', 'SINTETICA', '2', FALSE, NULL, 'PASSIVO_CIRCULANTE', NULL),

                                  ('2.1.01', 'Fornecedores', 'PASSIVO', 'CREDORA', 'SINTETICA', '2.1', FALSE, NULL, 'PASSIVO_CIRCULANTE', NULL),
                                  ('2.1.01.001', 'Fornecedores Nacionais', 'PASSIVO', 'CREDORA', 'ANALITICA', '2.1.01', FALSE, NULL, 'PASSIVO_CIRCULANTE', NULL),

                                  ('2.1.02', 'Obrigações Trabalhistas e Previdenciárias', 'PASSIVO', 'CREDORA', 'SINTETICA', '2.1', FALSE, NULL, 'PASSIVO_CIRCULANTE', NULL),
                                  ('2.1.02.001', 'Salários a Pagar', 'PASSIVO', 'CREDORA', 'ANALITICA', '2.1.02', FALSE, NULL, 'PASSIVO_CIRCULANTE', NULL),
                                  ('2.1.02.002', 'INSS a Recolher', 'PASSIVO', 'CREDORA', 'ANALITICA', '2.1.02', FALSE, NULL, 'PASSIVO_CIRCULANTE', NULL),
                                  ('2.1.02.003', 'FGTS a Recolher', 'PASSIVO', 'CREDORA', 'ANALITICA', '2.1.02', FALSE, NULL, 'PASSIVO_CIRCULANTE', NULL),

                                  ('2.1.03', 'Obrigações Tributárias', 'PASSIVO', 'CREDORA', 'SINTETICA', '2.1', FALSE, NULL, 'PASSIVO_CIRCULANTE', NULL),
                                  ('2.1.03.001', 'ICMS a Recolher', 'PASSIVO', 'CREDORA', 'ANALITICA', '2.1.03', FALSE, NULL, 'PASSIVO_CIRCULANTE', NULL),
                                  ('2.1.03.002', 'ISS a Recolher', 'PASSIVO', 'CREDORA', 'ANALITICA', '2.1.03', FALSE, NULL, 'PASSIVO_CIRCULANTE', NULL),
                                  ('2.1.03.003', 'PIS a Recolher', 'PASSIVO', 'CREDORA', 'ANALITICA', '2.1.03', FALSE, NULL, 'PASSIVO_CIRCULANTE', NULL),
                                  ('2.1.03.004', 'COFINS a Recolher', 'PASSIVO', 'CREDORA', 'ANALITICA', '2.1.03', FALSE, NULL, 'PASSIVO_CIRCULANTE', NULL),
                                  ('2.1.03.005', 'IPI a Recolher', 'PASSIVO', 'CREDORA', 'ANALITICA', '2.1.03', FALSE, NULL, 'PASSIVO_CIRCULANTE', NULL),
                                  ('2.1.03.006', 'IRRF a Recolher', 'PASSIVO', 'CREDORA', 'ANALITICA', '2.1.03', FALSE, NULL, 'PASSIVO_CIRCULANTE', NULL),

                                  ('2.1.04', 'Empréstimos e Financiamentos de Curto Prazo', 'PASSIVO', 'CREDORA', 'SINTETICA', '2.1', FALSE, NULL, 'PASSIVO_CIRCULANTE', NULL),
                                  ('2.1.04.001', 'Empréstimos Bancários de Curto Prazo', 'PASSIVO', 'CREDORA', 'ANALITICA', '2.1.04', FALSE, NULL, 'PASSIVO_CIRCULANTE', NULL),

                                  ('2.1.05', 'Outras Obrigações', 'PASSIVO', 'CREDORA', 'SINTETICA', '2.1', FALSE, NULL, 'PASSIVO_CIRCULANTE', NULL),
                                  ('2.1.05.001', 'Contas a Pagar', 'PASSIVO', 'CREDORA', 'ANALITICA', '2.1.05', FALSE, NULL, 'PASSIVO_CIRCULANTE', NULL),
                                  ('2.1.05.002', 'Adiantamentos de Clientes', 'PASSIVO', 'CREDORA', 'ANALITICA', '2.1.05', FALSE, NULL, 'PASSIVO_CIRCULANTE', NULL),
                                  ('2.1.05.003', 'Dividendos a Pagar', 'PASSIVO', 'CREDORA', 'ANALITICA', '2.1.05', FALSE, NULL, 'PASSIVO_CIRCULANTE', NULL),

                                  ('2.2', 'Passivo Não Circulante', 'PASSIVO', 'CREDORA', 'SINTETICA', '2', FALSE, NULL, 'PASSIVO_NAO_CIRCULANTE', NULL),
                                  ('2.2.01', 'Empréstimos e Financiamentos de Longo Prazo', 'PASSIVO', 'CREDORA', 'SINTETICA', '2.2', FALSE, NULL, 'PASSIVO_NAO_CIRCULANTE', NULL),
                                  ('2.2.01.001', 'Empréstimos Bancários de Longo Prazo', 'PASSIVO', 'CREDORA', 'ANALITICA', '2.2.01', FALSE, NULL, 'PASSIVO_NAO_CIRCULANTE', NULL),
                                  ('2.2.02', 'Outras Obrigações de Longo Prazo', 'PASSIVO', 'CREDORA', 'SINTETICA', '2.2', FALSE, NULL, 'PASSIVO_NAO_CIRCULANTE', NULL),
                                  ('2.2.02.001', 'Fornecedores de Longo Prazo', 'PASSIVO', 'CREDORA', 'ANALITICA', '2.2.02', FALSE, NULL, 'PASSIVO_NAO_CIRCULANTE', NULL);


-- =========================================================
-- 3. PATRIMÔNIO LÍQUIDO
-- =========================================================

INSERT INTO tmp_contas_padrao VALUES
                                  ('3', 'PATRIMÔNIO LÍQUIDO', 'PATRIMONIO_LIQUIDO', 'CREDORA', 'SINTETICA', NULL, FALSE, NULL, NULL, NULL),

                                  ('3.1', 'Capital Social', 'PATRIMONIO_LIQUIDO', 'CREDORA', 'SINTETICA', '3', FALSE, NULL, 'PATRIMONIO_LIQUIDO', NULL),
                                  ('3.1.001', 'Capital Social Subscrito', 'PATRIMONIO_LIQUIDO', 'CREDORA', 'ANALITICA', '3.1', FALSE, NULL, 'PATRIMONIO_LIQUIDO', NULL),
                                  ('3.1.002', '(-) Capital Social a Integralizar', 'PATRIMONIO_LIQUIDO', 'DEVEDORA', 'ANALITICA', '3.1', TRUE, '3.1.001', 'PATRIMONIO_LIQUIDO', NULL),

                                  ('3.2', 'Reservas', 'PATRIMONIO_LIQUIDO', 'CREDORA', 'SINTETICA', '3', FALSE, NULL, 'PATRIMONIO_LIQUIDO', NULL),
                                  ('3.2.001', 'Reservas de Capital', 'PATRIMONIO_LIQUIDO', 'CREDORA', 'ANALITICA', '3.2', FALSE, NULL, 'PATRIMONIO_LIQUIDO', NULL),
                                  ('3.2.002', 'Reserva Legal', 'PATRIMONIO_LIQUIDO', 'CREDORA', 'ANALITICA', '3.2', FALSE, NULL, 'PATRIMONIO_LIQUIDO', NULL),
                                  ('3.2.003', 'Reservas Estatutárias e Outras Reservas', 'PATRIMONIO_LIQUIDO', 'CREDORA', 'ANALITICA', '3.2', FALSE, NULL, 'PATRIMONIO_LIQUIDO', NULL),

                                  ('3.3', 'Resultados Acumulados', 'PATRIMONIO_LIQUIDO', 'CREDORA', 'SINTETICA', '3', FALSE, NULL, 'PATRIMONIO_LIQUIDO', NULL),
                                  ('3.3.001', 'Lucros Acumulados', 'PATRIMONIO_LIQUIDO', 'CREDORA', 'ANALITICA', '3.3', FALSE, NULL, 'PATRIMONIO_LIQUIDO', NULL),
                                  ('3.3.002', 'Prejuízos Acumulados', 'PATRIMONIO_LIQUIDO', 'DEVEDORA', 'ANALITICA', '3.3', FALSE, NULL, 'PATRIMONIO_LIQUIDO', NULL);


-- =========================================================
-- 4. RECEITAS
-- =========================================================

INSERT INTO tmp_contas_padrao VALUES
                                  ('4', 'RECEITAS', 'RECEITA', 'CREDORA', 'SINTETICA', NULL, FALSE, NULL, NULL, NULL),

                                  ('4.1', 'Receita Bruta', 'RECEITA', 'CREDORA', 'SINTETICA', '4', FALSE, NULL, NULL, 'RECEITA_BRUTA'),
                                  ('4.1.001', 'Receita de Venda de Mercadorias', 'RECEITA', 'CREDORA', 'ANALITICA', '4.1', FALSE, NULL, NULL, 'RECEITA_BRUTA'),
                                  ('4.1.002', 'Receita de Venda de Produtos', 'RECEITA', 'CREDORA', 'ANALITICA', '4.1', FALSE, NULL, NULL, 'RECEITA_BRUTA'),
                                  ('4.1.003', 'Receita de Prestação de Serviços', 'RECEITA', 'CREDORA', 'ANALITICA', '4.1', FALSE, NULL, NULL, 'RECEITA_BRUTA'),

                                  ('4.2', 'Deduções da Receita Bruta', 'RECEITA', 'DEVEDORA', 'SINTETICA', '4', FALSE, NULL, NULL, 'DEDUCOES_RECEITA'),
                                  ('4.2.001', 'ICMS sobre Vendas', 'RECEITA', 'DEVEDORA', 'ANALITICA', '4.2', FALSE, NULL, NULL, 'DEDUCOES_RECEITA'),
                                  ('4.2.002', 'ISS sobre Serviços', 'RECEITA', 'DEVEDORA', 'ANALITICA', '4.2', FALSE, NULL, NULL, 'DEDUCOES_RECEITA'),
                                  ('4.2.003', 'PIS sobre Receita', 'RECEITA', 'DEVEDORA', 'ANALITICA', '4.2', FALSE, NULL, NULL, 'DEDUCOES_RECEITA'),
                                  ('4.2.004', 'COFINS sobre Receita', 'RECEITA', 'DEVEDORA', 'ANALITICA', '4.2', FALSE, NULL, NULL, 'DEDUCOES_RECEITA'),
                                  ('4.2.005', 'Devoluções e Cancelamentos de Vendas', 'RECEITA', 'DEVEDORA', 'ANALITICA', '4.2', FALSE, NULL, NULL, 'DEDUCOES_RECEITA'),
                                  ('4.2.006', 'Descontos Incondicionais Concedidos', 'RECEITA', 'DEVEDORA', 'ANALITICA', '4.2', FALSE, NULL, NULL, 'DEDUCOES_RECEITA'),

                                  ('4.3', 'Receitas Financeiras', 'RECEITA', 'CREDORA', 'SINTETICA', '4', FALSE, NULL, NULL, 'RESULTADO_FINANCEIRO'),
                                  ('4.3.001', 'Rendimentos de Aplicações Financeiras', 'RECEITA', 'CREDORA', 'ANALITICA', '4.3', FALSE, NULL, NULL, 'RESULTADO_FINANCEIRO'),
                                  ('4.3.002', 'Juros Ativos', 'RECEITA', 'CREDORA', 'ANALITICA', '4.3', FALSE, NULL, NULL, 'RESULTADO_FINANCEIRO'),
                                  ('4.3.003', 'Descontos Financeiros Obtidos', 'RECEITA', 'CREDORA', 'ANALITICA', '4.3', FALSE, NULL, NULL, 'RESULTADO_FINANCEIRO'),

                                  ('4.4', 'Outras Receitas', 'RECEITA', 'CREDORA', 'SINTETICA', '4', FALSE, NULL, NULL, 'OUTRAS_RECEITAS'),
                                  ('4.4.001', 'Outras Receitas Operacionais', 'RECEITA', 'CREDORA', 'ANALITICA', '4.4', FALSE, NULL, NULL, 'OUTRAS_RECEITAS'),
                                  ('4.4.002', 'Ganhos na Alienação de Ativos', 'RECEITA', 'CREDORA', 'ANALITICA', '4.4', FALSE, NULL, NULL, 'OUTRAS_RECEITAS');


-- =========================================================
-- 5. CUSTOS
-- =========================================================

INSERT INTO tmp_contas_padrao VALUES
                                  ('5', 'CUSTOS', 'CUSTO', 'DEVEDORA', 'SINTETICA', NULL, FALSE, NULL, NULL, NULL),

                                  ('5.1', 'Custos dos Produtos e Serviços', 'CUSTO', 'DEVEDORA', 'SINTETICA', '5', FALSE, NULL, NULL, 'CUSTOS'),
                                  ('5.1.001', 'Custo das Mercadorias Vendidas (CMV)', 'CUSTO', 'DEVEDORA', 'ANALITICA', '5.1', FALSE, NULL, NULL, 'CUSTOS'),
                                  ('5.1.002', 'Custo dos Produtos Vendidos (CPV)', 'CUSTO', 'DEVEDORA', 'ANALITICA', '5.1', FALSE, NULL, NULL, 'CUSTOS'),
                                  ('5.1.003', 'Custo dos Serviços Prestados (CSP)', 'CUSTO', 'DEVEDORA', 'ANALITICA', '5.1', FALSE, NULL, NULL, 'CUSTOS'),

                                  ('5.2', 'Custos de Produção', 'CUSTO', 'DEVEDORA', 'SINTETICA', '5', FALSE, NULL, NULL, 'CUSTOS'),
                                  ('5.2.001', 'Matéria-Prima Consumida', 'CUSTO', 'DEVEDORA', 'ANALITICA', '5.2', FALSE, NULL, NULL, 'CUSTOS'),
                                  ('5.2.002', 'Mão de Obra Direta', 'CUSTO', 'DEVEDORA', 'ANALITICA', '5.2', FALSE, NULL, NULL, 'CUSTOS'),
                                  ('5.2.003', 'Custos Indiretos de Fabricação', 'CUSTO', 'DEVEDORA', 'ANALITICA', '5.2', FALSE, NULL, NULL, 'CUSTOS'),
                                  ('5.2.004', 'Depreciação Aplicada à Produção', 'CUSTO', 'DEVEDORA', 'ANALITICA', '5.2', FALSE, NULL, NULL, 'CUSTOS');


-- =========================================================
-- 6. DESPESAS
-- =========================================================

INSERT INTO tmp_contas_padrao VALUES
                                  ('6', 'DESPESAS', 'DESPESA', 'DEVEDORA', 'SINTETICA', NULL, FALSE, NULL, NULL, NULL),

                                  ('6.1', 'Despesas Comerciais', 'DESPESA', 'DEVEDORA', 'SINTETICA', '6', FALSE, NULL, NULL, 'DESPESAS_OPERACIONAIS'),
                                  ('6.1.001', 'Salários e Encargos Comerciais', 'DESPESA', 'DEVEDORA', 'ANALITICA', '6.1', FALSE, NULL, NULL, 'DESPESAS_OPERACIONAIS'),
                                  ('6.1.002', 'Comissões sobre Vendas', 'DESPESA', 'DEVEDORA', 'ANALITICA', '6.1', FALSE, NULL, NULL, 'DESPESAS_OPERACIONAIS'),
                                  ('6.1.003', 'Publicidade e Propaganda', 'DESPESA', 'DEVEDORA', 'ANALITICA', '6.1', FALSE, NULL, NULL, 'DESPESAS_OPERACIONAIS'),
                                  ('6.1.004', 'Fretes sobre Vendas', 'DESPESA', 'DEVEDORA', 'ANALITICA', '6.1', FALSE, NULL, NULL, 'DESPESAS_OPERACIONAIS'),
                                  ('6.1.005', 'Depreciação de Ativos Comerciais', 'DESPESA', 'DEVEDORA', 'ANALITICA', '6.1', FALSE, NULL, NULL, 'DESPESAS_OPERACIONAIS'),

                                  ('6.2', 'Despesas Administrativas', 'DESPESA', 'DEVEDORA', 'SINTETICA', '6', FALSE, NULL, NULL, 'DESPESAS_OPERACIONAIS'),
                                  ('6.2.001', 'Salários e Encargos Administrativos', 'DESPESA', 'DEVEDORA', 'ANALITICA', '6.2', FALSE, NULL, NULL, 'DESPESAS_OPERACIONAIS'),
                                  ('6.2.002', 'Aluguéis', 'DESPESA', 'DEVEDORA', 'ANALITICA', '6.2', FALSE, NULL, NULL, 'DESPESAS_OPERACIONAIS'),
                                  ('6.2.003', 'Água, Energia e Telefonia', 'DESPESA', 'DEVEDORA', 'ANALITICA', '6.2', FALSE, NULL, NULL, 'DESPESAS_OPERACIONAIS'),
                                  ('6.2.004', 'Serviços de Terceiros', 'DESPESA', 'DEVEDORA', 'ANALITICA', '6.2', FALSE, NULL, NULL, 'DESPESAS_OPERACIONAIS'),
                                  ('6.2.005', 'Material de Escritório e Consumo', 'DESPESA', 'DEVEDORA', 'ANALITICA', '6.2', FALSE, NULL, NULL, 'DESPESAS_OPERACIONAIS'),
                                  ('6.2.006', 'Depreciação e Amortização Administrativa', 'DESPESA', 'DEVEDORA', 'ANALITICA', '6.2', FALSE, NULL, NULL, 'DESPESAS_OPERACIONAIS'),

                                  ('6.3', 'Despesas Financeiras', 'DESPESA', 'DEVEDORA', 'SINTETICA', '6', FALSE, NULL, NULL, 'RESULTADO_FINANCEIRO'),
                                  ('6.3.001', 'Juros Passivos', 'DESPESA', 'DEVEDORA', 'ANALITICA', '6.3', FALSE, NULL, NULL, 'RESULTADO_FINANCEIRO'),
                                  ('6.3.002', 'Tarifas Bancárias', 'DESPESA', 'DEVEDORA', 'ANALITICA', '6.3', FALSE, NULL, NULL, 'RESULTADO_FINANCEIRO'),
                                  ('6.3.003', 'Descontos Financeiros Concedidos', 'DESPESA', 'DEVEDORA', 'ANALITICA', '6.3', FALSE, NULL, NULL, 'RESULTADO_FINANCEIRO'),

                                  ('6.4', 'Tributos sobre o Resultado', 'DESPESA', 'DEVEDORA', 'SINTETICA', '6', FALSE, NULL, NULL, 'TRIBUTOS_SOBRE_RESULTADO'),
                                  ('6.4.001', 'Imposto de Renda da Pessoa Jurídica (IRPJ)', 'DESPESA', 'DEVEDORA', 'ANALITICA', '6.4', FALSE, NULL, NULL, 'TRIBUTOS_SOBRE_RESULTADO'),
                                  ('6.4.002', 'Contribuição Social sobre o Lucro Líquido (CSLL)', 'DESPESA', 'DEVEDORA', 'ANALITICA', '6.4', FALSE, NULL, NULL, 'TRIBUTOS_SOBRE_RESULTADO'),

                                  ('6.5', 'Outras Despesas', 'DESPESA', 'DEVEDORA', 'SINTETICA', '6', FALSE, NULL, NULL, 'OUTRAS_DESPESAS'),
                                  ('6.5.001', 'Outras Despesas Operacionais', 'DESPESA', 'DEVEDORA', 'ANALITICA', '6.5', FALSE, NULL, NULL, 'OUTRAS_DESPESAS'),
                                  ('6.5.002', 'Perdas na Alienação de Ativos', 'DESPESA', 'DEVEDORA', 'ANALITICA', '6.5', FALSE, NULL, NULL, 'OUTRAS_DESPESAS');


-- =========================================================
-- 7. INSERÇÃO DAS CONTAS NO MODELO PADRÃO
-- =========================================================

INSERT INTO modelo_conta_contabil (
    modelo_versao,
    codigo,
    nome,
    grupo,
    natureza,
    tipo,
    conta_redutora,
    classificacao_bp,
    classificacao_dre
)
SELECT
    1,
    codigo,
    nome,
    grupo,
    natureza,
    tipo,
    FALSE,
    classificacao_bp,
    classificacao_dre
FROM tmp_contas_padrao
ORDER BY codigo;


-- =========================================================
-- 8. VINCULAÇÃO DAS CONTAS PAI
-- =========================================================

UPDATE modelo_conta_contabil filha
SET conta_pai_id = pai.id
    FROM tmp_contas_padrao dados_filha
JOIN modelo_conta_contabil pai
ON pai.modelo_versao = 1
    AND pai.codigo = dados_filha.codigo_pai
WHERE filha.modelo_versao = 1
  AND filha.codigo = dados_filha.codigo
  AND dados_filha.codigo_pai IS NOT NULL;


-- =========================================================
-- 9. VINCULAÇÃO DAS CONTAS REDUTORAS
-- =========================================================

UPDATE modelo_conta_contabil redutora
SET
    conta_redutora = TRUE,
    conta_reduzida_id = reduzida.id
    FROM tmp_contas_padrao dados
JOIN modelo_conta_contabil reduzida
ON reduzida.modelo_versao = 1
    AND reduzida.codigo = dados.codigo_conta_reduzida
WHERE redutora.modelo_versao = 1
  AND redutora.codigo = dados.codigo
  AND dados.conta_redutora = TRUE;
