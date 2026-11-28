ALTER TABLE ativo_patrimonial
    ADD COLUMN lancamento_abertura_id UUID,
    ADD COLUMN depreciacao_inicial NUMERIC(19,2) NOT NULL DEFAULT 0,
    ADD COLUMN meses_consumidos_inicio INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN inicio_depreciacao_lex DATE,
    ADD CONSTRAINT fk_ativo_patrimonial_abertura FOREIGN KEY (lancamento_abertura_id, plano_contas_id)
        REFERENCES lancamento_contabil (id, plano_contas_id),
    ADD CONSTRAINT ck_ativo_patrimonial_importacao CHECK (
        (lancamento_abertura_id IS NULL AND inicio_depreciacao_lex IS NULL
            AND depreciacao_inicial = 0 AND meses_consumidos_inicio = 0)
        OR (lancamento_abertura_id IS NOT NULL AND lancamento_aquisicao_id IS NULL
            AND inicio_depreciacao_lex IS NOT NULL AND EXTRACT(DAY FROM inicio_depreciacao_lex) = 1
            AND depreciacao_inicial BETWEEN 0 AND custo_aquisicao - valor_residual
            AND meses_consumidos_inicio BETWEEN 0 AND COALESCE(vida_util_meses, 0)
            AND (depreciacao_inicial = 0 OR meses_consumidos_inicio > 0)
            AND (meses_consumidos_inicio = 0 OR (depreciavel AND data_disponibilidade IS NOT NULL))
            AND (NOT depreciavel OR meses_consumidos_inicio < vida_util_meses
                 OR depreciacao_inicial = custo_aquisicao - valor_residual))
    );

CREATE INDEX ix_ativo_patrimonial_abertura
    ON ativo_patrimonial (empresa_id, lancamento_abertura_id)
    WHERE lancamento_abertura_id IS NOT NULL;

-- Mantém o caminho de aquisição da V9 e valida separadamente a referência de migração.
CREATE OR REPLACE FUNCTION exigir_aquisicao_ativo_contabilizada() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
DECLARE
    ativo ativo_patrimonial%ROWTYPE;
BEGIN
    SELECT * INTO ativo FROM ativo_patrimonial WHERE id = NEW.id;
    IF NOT FOUND THEN
        RETURN NULL;
    END IF;

    IF ativo.lancamento_abertura_id IS NULL THEN
        IF NOT EXISTS (
            SELECT 1 FROM lancamento_contabil l
            WHERE l.id = ativo.lancamento_aquisicao_id AND l.empresa_id = ativo.empresa_id
              AND l.plano_contas_id = ativo.plano_contas_id AND l.data = ativo.data_aquisicao
              AND l.origem = 'ATIVO' AND l.status = 'CONFIRMADO'
              AND l.confirmado_por = ativo.usuario_id
        ) THEN
            RAISE EXCEPTION 'Ativo patrimonial exige aquisição contabilizada e confirmada.'
                USING ERRCODE = '23514', CONSTRAINT = 'ck_ativo_patrimonial_contabilizado';
        END IF;
        RETURN NULL;
    END IF;

    -- A atualização de disponibilidade mantém o vínculo e já bloqueou o calendário.
    IF TG_OP = 'INSERT' OR NEW.lancamento_abertura_id IS DISTINCT FROM OLD.lancamento_abertura_id THEN
        PERFORM 1 FROM lancamento_contabil WHERE id = ativo.lancamento_abertura_id FOR SHARE;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM lancamento_contabil l
        JOIN exercicio_contabil e ON e.id = l.exercicio_id AND e.empresa_id = l.empresa_id
        WHERE l.id = ativo.lancamento_abertura_id AND l.empresa_id = ativo.empresa_id
          AND l.plano_contas_id = ativo.plano_contas_id AND l.origem = 'ABERTURA' AND l.status = 'CONFIRMADO'
          AND ativo.data_aquisicao <= l.data AND e.exercicio_origem_id IS NULL
          AND NOT EXISTS (SELECT 1 FROM exercicio_contabil anterior
                          WHERE anterior.empresa_id = e.empresa_id AND anterior.ano < e.ano)
          AND (ativo.meses_consumidos_inicio = 0 OR ativo.data_disponibilidade <= l.data)
          AND (ativo.inicio_depreciacao_lex = date_trunc('month', l.data::timestamp)::date
               OR (ativo.meses_consumidos_inicio > 0 AND ativo.inicio_depreciacao_lex =
                   (date_trunc('month', l.data::timestamp) + interval '1 month')::date))
    ) THEN
        RAISE EXCEPTION 'Ativo importado exige abertura de migração confirmada na mesma empresa e plano.'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_ativo_patrimonial_abertura_confirmada';
    END IF;
    RETURN NULL;
END;
$$;

CREATE FUNCTION proteger_abertura_com_fichas_importadas() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.origem = 'ABERTURA' AND NEW.status = 'ESTORNADO' AND EXISTS (
        SELECT 1 FROM ativo_patrimonial
        WHERE empresa_id = OLD.empresa_id AND lancamento_abertura_id = OLD.id
    ) THEN
        RAISE EXCEPTION 'A abertura vinculada a fichas importadas exige reversão pelo módulo patrimonial.'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_abertura_patrimonial_sem_estorno';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_proteger_abertura_com_fichas_importadas
BEFORE UPDATE OF status ON lancamento_contabil
FOR EACH ROW EXECUTE FUNCTION proteger_abertura_com_fichas_importadas();

CREATE FUNCTION validar_marco_depreciacao_importada() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM ativo_patrimonial a
        WHERE a.empresa_id = NEW.empresa_id AND a.id = NEW.ativo_id AND a.lancamento_abertura_id IS NOT NULL
          AND (NEW.competencia < a.inicio_depreciacao_lex
               OR a.meses_consumidos_inicio >= a.vida_util_meses
               OR a.depreciacao_inicial >= a.custo_aquisicao - a.valor_residual)
    ) THEN
        RAISE EXCEPTION 'Apropriação fora do marco ou sem base e vida útil remanescentes na importação.'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_depreciacao_marco_importacao';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_validar_marco_depreciacao_importada
BEFORE INSERT OR UPDATE ON apropriacao_depreciacao
FOR EACH ROW EXECUTE FUNCTION validar_marco_depreciacao_importada();
