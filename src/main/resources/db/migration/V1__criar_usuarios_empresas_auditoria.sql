-- =========================================================
-- LEX - V1
-- Usuarios, empresas e auditoria
-- PostgreSQL 18
-- =========================================================


-- =========================================================
-- 1. USUARIO
-- =========================================================

CREATE TABLE usuario (
                         id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

                         nome VARCHAR(150) NOT NULL,

                         email VARCHAR(254) NOT NULL,

                         senha_hash VARCHAR(255) NOT NULL,

                         ativo BOOLEAN NOT NULL DEFAULT TRUE,

                         criado_em TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

                         CONSTRAINT ck_usuario_nome
                             CHECK (BTRIM(nome) <> ''),

                         CONSTRAINT ck_usuario_email
                             CHECK (BTRIM(email) <> ''),

                         CONSTRAINT ck_usuario_senha_hash
                             CHECK (BTRIM(senha_hash) <> '')
);


-- E-mail não diferencia maiúsculas de minúsculas.
CREATE UNIQUE INDEX uk_usuario_email
    ON usuario (LOWER(email));


-- =========================================================
-- 2. EMPRESA
-- =========================================================

CREATE TABLE empresa (
                         id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

                         usuario_id UUID NOT NULL,

                         razao_social VARCHAR(200) NOT NULL,

                         nome_fantasia VARCHAR(200),

                         cnpj VARCHAR(14),

                         ativa BOOLEAN NOT NULL DEFAULT TRUE,

                         arquivada_em TIMESTAMPTZ,

                         arquivada_por UUID,

                         motivo_arquivamento TEXT,

                         criado_em TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

                         CONSTRAINT fk_empresa_usuario
                             FOREIGN KEY (usuario_id)
                                 REFERENCES usuario (id)
                                 ON DELETE RESTRICT,

                         CONSTRAINT fk_empresa_arquivada_por
                             FOREIGN KEY (arquivada_por)
                                 REFERENCES usuario (id)
                                 ON DELETE RESTRICT,

                         CONSTRAINT ck_empresa_razao_social
                             CHECK (BTRIM(razao_social) <> ''),

                         CONSTRAINT ck_empresa_cnpj
                             CHECK (
                                 cnpj IS NULL
                                     OR cnpj ~ '^[0-9]{14}$'
),

    CONSTRAINT ck_empresa_arquivamento
        CHECK (
            (
                arquivada_em IS NULL
                AND arquivada_por IS NULL
                AND motivo_arquivamento IS NULL
            )
            OR
            (
                arquivada_em IS NOT NULL
                AND motivo_arquivamento IS NOT NULL
                AND BTRIM(motivo_arquivamento) <> ''
                AND ativa = FALSE
            )
        )
);


-- Um CNPJ não pode estar cadastrado em duas empresas.
-- Empresas sem CNPJ podem existir.
CREATE UNIQUE INDEX uk_empresa_cnpj
    ON empresa (cnpj)
    WHERE cnpj IS NOT NULL;


-- Facilita localizar empresas pelo proprietário.
CREATE INDEX idx_empresa_usuario
    ON empresa (usuario_id);


-- =========================================================
-- 3. AUDITORIA
-- =========================================================

CREATE TABLE auditoria_evento (
                                  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

                                  empresa_id UUID,

                                  usuario_id UUID,

                                  entidade VARCHAR(80) NOT NULL,

                                  entidade_id UUID,

                                  acao VARCHAR(40) NOT NULL,

                                  dados_anteriores JSONB,

                                  dados_novos JSONB,

                                  ocorrido_em TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

                                  CONSTRAINT fk_auditoria_empresa
                                      FOREIGN KEY (empresa_id)
                                          REFERENCES empresa (id)
                                          ON DELETE RESTRICT,

                                  CONSTRAINT fk_auditoria_usuario
                                      FOREIGN KEY (usuario_id)
                                          REFERENCES usuario (id)
                                          ON DELETE SET NULL,

                                  CONSTRAINT ck_auditoria_entidade
                                      CHECK (BTRIM(entidade) <> ''),

                                  CONSTRAINT ck_auditoria_acao
                                      CHECK (BTRIM(acao) <> '')
);


-- Consulta do histórico de uma empresa.
CREATE INDEX idx_auditoria_empresa_data
    ON auditoria_evento (empresa_id, ocorrido_em DESC);


-- Consulta das ações realizadas por um usuário.
CREATE INDEX idx_auditoria_usuario_data
    ON auditoria_evento (usuario_id, ocorrido_em DESC);


-- Consulta do histórico de uma entidade.
CREATE INDEX idx_auditoria_entidade
    ON auditoria_evento (entidade, entidade_id);


-- =========================================================
-- 4. PROTECAO DOS REGISTROS DE AUDITORIA
-- =========================================================

CREATE FUNCTION impedir_alteracao_auditoria()
    RETURNS TRIGGER
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION
        'Registros de auditoria não podem ser alterados ou excluídos.';

RETURN NULL;
END;
$$;


CREATE TRIGGER trg_auditoria_imutavel
    BEFORE UPDATE OR DELETE
ON auditoria_evento
FOR EACH ROW
EXECUTE FUNCTION impedir_alteracao_auditoria();