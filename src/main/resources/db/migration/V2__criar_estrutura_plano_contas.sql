
-- =========================================================
-- LEX - V2
-- Estrutura do plano de contas
-- PostgreSQL 18
-- =========================================================

-- ---------------------------------------------------------
-- 1. MODELO PADRÃO DE PLANO DE CONTAS
-- ---------------------------------------------------------

CREATE TABLE modelo_plano_contas (
                                     versao INTEGER PRIMARY KEY,
                                     descricao VARCHAR(200) NOT NULL,
                                     ativo BOOLEAN NOT NULL DEFAULT TRUE,
                                     criado_em TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

                                     CONSTRAINT ck_modelo_plano_versao
                                         CHECK (versao > 0),

                                     CONSTRAINT ck_modelo_plano_descricao
                                         CHECK (BTRIM(descricao) <> '')
);

-- Versão inicial do modelo padrão.
INSERT INTO modelo_plano_contas (versao, descricao)
VALUES (1, 'Plano de contas padrão LEX - versão 1');


-- ---------------------------------------------------------
-- 2. CONTAS DO MODELO PADRÃO
-- ---------------------------------------------------------

CREATE TABLE modelo_conta_contabil (
                                       id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                                       modelo_versao INTEGER NOT NULL,
                                       conta_pai_id UUID,

                                       codigo VARCHAR(30) NOT NULL,
                                       nome VARCHAR(200) NOT NULL,

                                       grupo VARCHAR(20) NOT NULL,
                                       natureza VARCHAR(10) NOT NULL,
                                       tipo VARCHAR(12) NOT NULL,

                                       conta_redutora BOOLEAN NOT NULL DEFAULT FALSE,
                                       conta_reduzida_id UUID,

                                       classificacao_bp VARCHAR(30),
                                       classificacao_dre VARCHAR(30),

                                       CONSTRAINT fk_modelo_conta_versao
                                           FOREIGN KEY (modelo_versao)
                                               REFERENCES modelo_plano_contas (versao)
                                               ON DELETE RESTRICT,

                                       CONSTRAINT fk_modelo_conta_pai
                                           FOREIGN KEY (modelo_versao, conta_pai_id)
                                               REFERENCES modelo_conta_contabil (modelo_versao, id)
                                               ON DELETE RESTRICT,

                                       CONSTRAINT fk_modelo_conta_reduzida
                                           FOREIGN KEY (modelo_versao, conta_reduzida_id)
                                               REFERENCES modelo_conta_contabil (modelo_versao, id)
                                               ON DELETE RESTRICT,

                                       CONSTRAINT uq_modelo_conta_versao_id
                                           UNIQUE (modelo_versao, id),

                                       CONSTRAINT uq_modelo_conta_codigo
                                           UNIQUE (modelo_versao, codigo),

                                       CONSTRAINT ck_modelo_conta_codigo
                                           CHECK (BTRIM(codigo) <> ''),

                                       CONSTRAINT ck_modelo_conta_nome
                                           CHECK (BTRIM(nome) <> ''),

                                       CONSTRAINT ck_modelo_conta_grupo
                                           CHECK (
                                               grupo IN (
                                                         'ATIVO',
                                                         'PASSIVO',
                                                         'PATRIMONIO_LIQUIDO',
                                                         'RECEITA',
                                                         'CUSTO',
                                                         'DESPESA'
                                                   )
                                               ),

                                       CONSTRAINT ck_modelo_conta_natureza
                                           CHECK (natureza IN ('DEVEDORA', 'CREDORA')),

                                       CONSTRAINT ck_modelo_conta_tipo
                                           CHECK (tipo IN ('SINTETICA', 'ANALITICA')),

                                       CONSTRAINT ck_modelo_conta_redutora
                                           CHECK (
                                               (conta_redutora = FALSE AND conta_reduzida_id IS NULL)
                                                   OR
                                               (conta_redutora = TRUE AND conta_reduzida_id IS NOT NULL)
                                               ),

                                       CONSTRAINT ck_modelo_conta_classificacao_bp
                                           CHECK (
                                               classificacao_bp IS NULL
                                                   OR classificacao_bp IN (
                                                                           'ATIVO_CIRCULANTE',
                                                                           'ATIVO_NAO_CIRCULANTE',
                                                                           'PASSIVO_CIRCULANTE',
                                                                           'PASSIVO_NAO_CIRCULANTE',
                                                                           'PATRIMONIO_LIQUIDO'
                                                   )
                                               ),

                                       CONSTRAINT ck_modelo_conta_classificacao_dre
                                           CHECK (
                                               classificacao_dre IS NULL
                                                   OR classificacao_dre IN (
                                                                            'RECEITA_BRUTA',
                                                                            'DEDUCOES_RECEITA',
                                                                            'CUSTOS',
                                                                            'DESPESAS_OPERACIONAIS',
                                                                            'RESULTADO_FINANCEIRO',
                                                                            'OUTRAS_RECEITAS',
                                                                            'OUTRAS_DESPESAS',
                                                                            'TRIBUTOS_SOBRE_RESULTADO'
                                                   )
                                               )
);

CREATE INDEX idx_modelo_conta_pai
    ON modelo_conta_contabil (modelo_versao, conta_pai_id);


-- ---------------------------------------------------------
-- 3. PLANO DE CONTAS DE CADA EMPRESA
-- ---------------------------------------------------------

CREATE TABLE plano_contas (
                              id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                              empresa_id UUID NOT NULL,
                              modelo_versao INTEGER NOT NULL,
                              criado_em TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

                              CONSTRAINT fk_plano_contas_empresa
                                  FOREIGN KEY (empresa_id)
                                      REFERENCES empresa (id)
                                      ON DELETE RESTRICT,

                              CONSTRAINT fk_plano_contas_modelo
                                  FOREIGN KEY (modelo_versao)
                                      REFERENCES modelo_plano_contas (versao)
                                      ON DELETE RESTRICT,

    -- Uma empresa terá um único plano de contas.
                              CONSTRAINT uq_plano_contas_empresa
                                  UNIQUE (empresa_id),

    -- Necessário para garantir a versão nas contas vinculadas.
                              CONSTRAINT uq_plano_contas_id_versao
                                  UNIQUE (id, modelo_versao)
);


-- ---------------------------------------------------------
-- 4. CONTAS CONTÁBEIS DE CADA EMPRESA
-- ---------------------------------------------------------

CREATE TABLE conta_contabil (
                                id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

                                plano_contas_id UUID NOT NULL,
                                modelo_versao INTEGER NOT NULL,
                                modelo_conta_id UUID NOT NULL,

                                conta_pai_id UUID,

                                codigo VARCHAR(30) NOT NULL,
                                nome VARCHAR(200) NOT NULL,

                                grupo VARCHAR(20) NOT NULL,
                                natureza VARCHAR(10) NOT NULL,
                                tipo VARCHAR(12) NOT NULL,

                                ativa BOOLEAN NOT NULL DEFAULT TRUE,

                                conta_redutora BOOLEAN NOT NULL DEFAULT FALSE,
                                conta_reduzida_id UUID,

                                classificacao_bp VARCHAR(30),
                                classificacao_dre VARCHAR(30),

                                CONSTRAINT fk_conta_plano_versao
                                    FOREIGN KEY (plano_contas_id, modelo_versao)
                                        REFERENCES plano_contas (id, modelo_versao)
                                        ON DELETE RESTRICT,

                                CONSTRAINT fk_conta_modelo
                                    FOREIGN KEY (modelo_versao, modelo_conta_id)
                                        REFERENCES modelo_conta_contabil (modelo_versao, id)
                                        ON DELETE RESTRICT,

                                CONSTRAINT fk_conta_pai
                                    FOREIGN KEY (plano_contas_id, conta_pai_id)
                                        REFERENCES conta_contabil (plano_contas_id, id)
                                        ON DELETE RESTRICT,

                                CONSTRAINT fk_conta_reduzida
                                    FOREIGN KEY (plano_contas_id, conta_reduzida_id)
                                        REFERENCES conta_contabil (plano_contas_id, id)
                                        ON DELETE RESTRICT,

                                CONSTRAINT uq_conta_plano_id
                                    UNIQUE (plano_contas_id, id),

                                CONSTRAINT uq_conta_codigo
                                    UNIQUE (plano_contas_id, codigo),

                                CONSTRAINT uq_conta_modelo
                                    UNIQUE (plano_contas_id, modelo_conta_id),

                                CONSTRAINT ck_conta_codigo
                                    CHECK (BTRIM(codigo) <> ''),

                                CONSTRAINT ck_conta_nome
                                    CHECK (BTRIM(nome) <> ''),

                                CONSTRAINT ck_conta_grupo
                                    CHECK (
                                        grupo IN (
                                                  'ATIVO',
                                                  'PASSIVO',
                                                  'PATRIMONIO_LIQUIDO',
                                                  'RECEITA',
                                                  'CUSTO',
                                                  'DESPESA'
                                            )
                                        ),

                                CONSTRAINT ck_conta_natureza
                                    CHECK (natureza IN ('DEVEDORA', 'CREDORA')),

                                CONSTRAINT ck_conta_tipo
                                    CHECK (tipo IN ('SINTETICA', 'ANALITICA')),

                                CONSTRAINT ck_conta_redutora
                                    CHECK (
                                        (conta_redutora = FALSE AND conta_reduzida_id IS NULL)
                                            OR
                                        (conta_redutora = TRUE AND conta_reduzida_id IS NOT NULL)
                                        ),

                                CONSTRAINT ck_conta_classificacao_bp
                                    CHECK (
                                        classificacao_bp IS NULL
                                            OR classificacao_bp IN (
                                                                    'ATIVO_CIRCULANTE',
                                                                    'ATIVO_NAO_CIRCULANTE',
                                                                    'PASSIVO_CIRCULANTE',
                                                                    'PASSIVO_NAO_CIRCULANTE',
                                                                    'PATRIMONIO_LIQUIDO'
                                            )
                                        ),

                                CONSTRAINT ck_conta_classificacao_dre
                                    CHECK (
                                        classificacao_dre IS NULL
                                            OR classificacao_dre IN (
                                                                     'RECEITA_BRUTA',
                                                                     'DEDUCOES_RECEITA',
                                                                     'CUSTOS',
                                                                     'DESPESAS_OPERACIONAIS',
                                                                     'RESULTADO_FINANCEIRO',
                                                                     'OUTRAS_RECEITAS',
                                                                     'OUTRAS_DESPESAS',
                                                                     'TRIBUTOS_SOBRE_RESULTADO'
                                            )
                                        )
);

CREATE INDEX idx_conta_contabil_pai
    ON conta_contabil (plano_contas_id, conta_pai_id);

CREATE INDEX idx_conta_contabil_ativa
    ON conta_contabil (plano_contas_id, ativa);
