
-- =========================================================
-- LEX - V4
-- Exercícios e períodos contábeis
-- PostgreSQL 18
-- =========================================================


-- ---------------------------------------------------------
-- 1. EXERCÍCIOS CONTÁBEIS
-- ---------------------------------------------------------

CREATE TABLE exercicio_contabil (
                                    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

                                    empresa_id UUID NOT NULL,

                                    ano SMALLINT NOT NULL,

                                    data_inicio DATE NOT NULL,
                                    data_fim DATE NOT NULL,

                                    status VARCHAR(12) NOT NULL DEFAULT 'ABERTO',

                                    criado_em TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

                                    CONSTRAINT fk_exercicio_empresa
                                        FOREIGN KEY (empresa_id)
                                            REFERENCES empresa (id)
                                            ON DELETE RESTRICT,

    -- Uma empresa não pode ter dois exercícios do mesmo ano.
                                    CONSTRAINT uq_exercicio_empresa_ano
                                        UNIQUE (empresa_id, ano),

    -- Chave composta para relacionamentos futuros.
                                    CONSTRAINT uq_exercicio_id_empresa_ano
                                        UNIQUE (id, empresa_id, ano),

                                    CONSTRAINT ck_exercicio_ano
                                        CHECK (ano BETWEEN 1900 AND 9999),

    -- O exercício corresponde ao ano civil.
                                    CONSTRAINT ck_exercicio_datas
                                        CHECK (
                                            data_inicio = MAKE_DATE(ano, 1, 1)
                                                AND data_fim = MAKE_DATE(ano, 12, 31)
                                            ),

                                    CONSTRAINT ck_exercicio_status
                                        CHECK (status IN ('ABERTO', 'ENCERRADO'))
);


-- ---------------------------------------------------------
-- 2. PERÍODOS CONTÁBEIS MENSAIS
-- ---------------------------------------------------------

CREATE TABLE periodo_contabil (
                                  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

                                  exercicio_id UUID NOT NULL,
                                  empresa_id UUID NOT NULL,
                                  ano SMALLINT NOT NULL,

                                  mes SMALLINT NOT NULL,

                                  data_inicio DATE NOT NULL,
                                  data_fim DATE NOT NULL,

                                  status VARCHAR(12) NOT NULL DEFAULT 'ABERTO',

                                  criado_em TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

                                  CONSTRAINT fk_periodo_exercicio
                                      FOREIGN KEY (exercicio_id, empresa_id, ano)
                                          REFERENCES exercicio_contabil (id, empresa_id, ano)
                                          ON DELETE RESTRICT,

    -- Um exercício terá somente um período por mês.
                                  CONSTRAINT uq_periodo_exercicio_mes
                                      UNIQUE (exercicio_id, mes),

    -- Chave composta para relacionamentos futuros.
                                  CONSTRAINT uq_periodo_id_empresa
                                      UNIQUE (id, empresa_id),

                                  CONSTRAINT ck_periodo_ano
                                      CHECK (ano BETWEEN 1900 AND 9999),

                                  CONSTRAINT ck_periodo_mes
                                      CHECK (mes BETWEEN 1 AND 12),

    -- O período sempre começa no primeiro dia do mês
    -- e termina no último dia do mesmo mês.
                                  CONSTRAINT ck_periodo_datas
                                      CHECK (
                                          data_inicio = MAKE_DATE(ano, mes, 1)
                                              AND data_fim = (
                                              MAKE_DATE(ano, mes, 1)
                                                  + INTERVAL '1 month'
                                                  - INTERVAL '1 day'
                                              )::DATE
),

    CONSTRAINT ck_periodo_status
        CHECK (status IN ('ABERTO', 'ENCERRADO'))
);


-- ---------------------------------------------------------
-- 3. ÍNDICES
-- ---------------------------------------------------------

CREATE INDEX idx_exercicio_empresa_status
    ON exercicio_contabil (empresa_id, status);

CREATE INDEX idx_periodo_empresa_data
    ON periodo_contabil (empresa_id, data_inicio, data_fim);

CREATE INDEX idx_periodo_exercicio_status
    ON periodo_contabil (exercicio_id, status);
