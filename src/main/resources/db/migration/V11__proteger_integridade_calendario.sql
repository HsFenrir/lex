CREATE FUNCTION primeiro_mes_escrituracao(p_exercicio UUID) RETURNS INTEGER
LANGUAGE sql STABLE AS $$
    SELECT CASE WHEN e.exercicio_origem_id IS NOT NULL THEN 1 ELSE COALESCE(
        (SELECT extract(month FROM min(l.data))::integer FROM lancamento_contabil l
         WHERE l.exercicio_id = e.id AND l.origem = 'ABERTURA' AND l.status IN ('CONFIRMADO', 'ESTORNADO')),
        (SELECT min(p.mes)::integer FROM periodo_contabil p WHERE p.exercicio_id = e.id)
    ) END
    FROM exercicio_contabil e WHERE e.id = p_exercicio
$$;

CREATE FUNCTION proteger_ordem_exercicios() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF (NEW.id, NEW.empresa_id, NEW.ano) IS DISTINCT FROM (OLD.id, OLD.empresa_id, OLD.ano) THEN
            RAISE EXCEPTION 'A identidade do exercício contábil é imutável.'
                USING ERRCODE = '23514', CONSTRAINT = 'ck_exercicio_identidade_calendario';
        END IF;
        RETURN NEW;
    END IF;
    IF current_setting('transaction_isolation') <> 'read committed' THEN
        RAISE EXCEPTION 'O cadastro de exercícios exige READ COMMITTED.';
    END IF;
    -- Serializa somente a criação de exercícios, inclusive quando a empresa ainda não possui calendário.
    PERFORM pg_advisory_xact_lock(hashtextextended('lex:exercicios:' || NEW.empresa_id::text, 0));
    IF EXISTS (SELECT 1 FROM exercicio_contabil e WHERE e.empresa_id = NEW.empresa_id AND e.ano > NEW.ano) THEN
        RAISE EXCEPTION 'O exercício não pode anteceder um exercício já cadastrado.'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_exercicio_ordem_calendario';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_proteger_ordem_exercicios
BEFORE INSERT OR UPDATE ON exercicio_contabil FOR EACH ROW EXECUTE FUNCTION proteger_ordem_exercicios();

CREATE FUNCTION proteger_calendario_periodos() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
DECLARE
    exercicio UUID;
    mes_alvo INTEGER;
    estado VARCHAR(12);
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF (NEW.id, NEW.exercicio_id, NEW.empresa_id, NEW.ano, NEW.mes, NEW.data_inicio, NEW.data_fim)
            IS DISTINCT FROM (OLD.id, OLD.exercicio_id, OLD.empresa_id, OLD.ano, OLD.mes, OLD.data_inicio, OLD.data_fim) THEN
            RAISE EXCEPTION 'A identidade da competência contábil é imutável.'
                USING ERRCODE = '23514', CONSTRAINT = 'ck_periodo_identidade_calendario';
        END IF;
        IF NEW.status = OLD.status THEN
            RETURN NEW;
        END IF;
    END IF;
    IF current_setting('transaction_isolation') <> 'read committed' THEN
        RAISE EXCEPTION 'A alteração do calendário exige READ COMMITTED.';
    END IF;
    IF TG_OP = 'DELETE' THEN
        exercicio := OLD.exercicio_id;
        mes_alvo := OLD.mes;
    ELSE
        exercicio := NEW.exercicio_id;
        mes_alvo := NEW.mes;
    END IF;
    SELECT e.status INTO estado FROM exercicio_contabil e WHERE e.id = exercicio FOR NO KEY UPDATE;
    IF TG_OP IN ('INSERT', 'DELETE') AND (
        estado = 'ENCERRADO' OR EXISTS (
            SELECT 1 FROM periodo_contabil p WHERE p.exercicio_id = exercicio
                AND p.mes >= mes_alvo AND p.status = 'ENCERRADO'
        )
    ) THEN
        RAISE EXCEPTION 'A alteração afetaria calendário já encerrado.'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_periodo_calendario_encerrado';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    IF NEW.status = 'ENCERRADO' AND (
        TG_OP = 'INSERT' OR EXISTS (
            SELECT 1 FROM generate_series(primeiro_mes_escrituracao(exercicio), mes_alvo - 1) m(mes)
            WHERE NOT EXISTS (SELECT 1 FROM periodo_contabil p WHERE p.exercicio_id = exercicio AND p.mes = m.mes)
        )
    ) THEN
        RAISE EXCEPTION 'O encerramento exige calendário sem lacunas desde o início da escrituração.'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_periodo_calendario_continuo';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_proteger_calendario_periodos
BEFORE INSERT OR UPDATE OR DELETE ON periodo_contabil FOR EACH ROW EXECUTE FUNCTION proteger_calendario_periodos();
