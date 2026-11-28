CREATE INDEX ix_movimento_estoque_lancamento
    ON movimento_estoque (empresa_id, lancamento_id);

CREATE INDEX ix_alocacao_estoque_entrada
    ON alocacao_estoque (entrada_id);

CREATE OR REPLACE FUNCTION exigir_contabilizacao_estoque() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
DECLARE
    movimento movimento_estoque%ROWTYPE;
BEGIN
    SELECT * INTO movimento FROM movimento_estoque WHERE id = NEW.id;
    IF NOT FOUND THEN
        RETURN NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM lancamento_contabil l
        JOIN exercicio_contabil e ON e.id = l.exercicio_id AND e.empresa_id = l.empresa_id
        WHERE l.id = movimento.lancamento_id AND l.empresa_id = movimento.empresa_id
          AND l.exercicio_id = movimento.exercicio_id AND l.periodo_id = movimento.periodo_id
          AND l.data = movimento.data AND l.status = 'CONFIRMADO'
          AND (l.origem = 'ESTOQUE' OR (
              l.origem = 'ABERTURA' AND movimento.tipo = 'ENTRADA'
              AND e.exercicio_origem_id IS NULL
              AND NOT EXISTS (SELECT 1 FROM exercicio_contabil anterior
                              WHERE anterior.empresa_id = e.empresa_id AND anterior.ano < e.ano)
          ))
        FOR SHARE OF l
    ) THEN
        RAISE EXCEPTION 'Movimento de estoque exige contabilização confirmada na mesma empresa e data.'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_movimento_estoque_contabilizado';
    END IF;
    RETURN NULL;
END;
$$;

CREATE FUNCTION proteger_abertura_com_lotes_iniciais() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.origem = 'ABERTURA' AND NEW.status = 'ESTORNADO' AND EXISTS (
        SELECT 1 FROM movimento_estoque
        WHERE empresa_id = OLD.empresa_id AND lancamento_id = OLD.id
    ) THEN
        RAISE EXCEPTION 'A abertura vinculada a lotes iniciais exige reversão pelo módulo de estoque.'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_abertura_estoque_sem_estorno';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_proteger_abertura_com_lotes_iniciais
BEFORE UPDATE OF status ON lancamento_contabil
FOR EACH ROW EXECUTE FUNCTION proteger_abertura_com_lotes_iniciais();
