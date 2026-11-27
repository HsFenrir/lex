CREATE FUNCTION bloquear_e_validar_exercicios(p_empresa UUID, p_aguardar BOOLEAN) RETURNS INTEGER
LANGUAGE plpgsql VOLATILE AS $$
DECLARE
    chave BIGINT := hashtextextended('lex:exercicios:' || p_empresa::text, 0);
    ultimo INTEGER;
    lacunas TEXT;
BEGIN
    IF current_setting('transaction_isolation') <> 'read committed' THEN
        RAISE EXCEPTION 'A alteração da continuidade anual exige READ COMMITTED.';
    END IF;
    IF p_aguardar THEN
        PERFORM pg_advisory_xact_lock(chave);
    ELSIF NOT pg_try_advisory_xact_lock(chave) THEN
        -- O DELETE já bloqueou a linha. Evite esperar por um fechamento que precise dessa linha.
        RAISE EXCEPTION 'O calendário anual está em uso. Tente a exclusão novamente.'
            USING ERRCODE = '55P03';
    END IF;

    SELECT max(ano),
           string_agg(format('%s a %s (entre %s e %s)', anterior + 1, ano - 1, anterior, ano),
                      ', ' ORDER BY ano) FILTER (WHERE ano > anterior + 1)
        INTO ultimo, lacunas
    FROM (
        SELECT e.ano::integer AS ano, lag(e.ano::integer) OVER (ORDER BY e.ano) AS anterior
        FROM exercicio_contabil e WHERE e.empresa_id = p_empresa
    ) anos;

    IF lacunas IS NOT NULL THEN
        RAISE EXCEPTION 'Calendário anual legado com exercícios ausentes: %. Requer regularização administrativa.', lacunas
            USING ERRCODE = '23514', CONSTRAINT = 'ck_exercicio_lacuna_legada';
    END IF;
    RETURN ultimo;
END;
$$;

CREATE OR REPLACE FUNCTION proteger_ordem_exercicios() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
DECLARE
    ultimo INTEGER;
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF (NEW.id, NEW.empresa_id, NEW.ano) IS DISTINCT FROM (OLD.id, OLD.empresa_id, OLD.ano) THEN
            RAISE EXCEPTION 'A identidade do exercício contábil é imutável.'
                USING ERRCODE = '23514', CONSTRAINT = 'ck_exercicio_identidade_calendario';
        END IF;
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        ultimo := bloquear_e_validar_exercicios(OLD.empresa_id, false);
        -- Rejeite dependências antes que a FK tente bloquear períodos na ordem inversa à do fechamento.
        IF OLD.ano <> ultimo OR OLD.status = 'ENCERRADO' OR OLD.exercicio_origem_id IS NOT NULL OR EXISTS (
            SELECT 1 FROM periodo_contabil p WHERE p.exercicio_id = OLD.id
        ) THEN
            RAISE EXCEPTION 'Somente o último exercício aberto, sem períodos ou vínculo de continuidade, pode ser excluído.'
                USING ERRCODE = '23514', CONSTRAINT = 'ck_exercicio_exclusao_continuidade';
        END IF;
        RETURN OLD;
    END IF;

    ultimo := bloquear_e_validar_exercicios(NEW.empresa_id, true);
    -- A unicidade já trata a duplicação do último ano. A soma usa INTEGER, inclusive no limite de 9999.
    IF ultimo IS NOT NULL AND NEW.ano <> ultimo AND (ultimo = 9999 OR NEW.ano <> ultimo + 1) THEN
        IF ultimo = 9999 AND NEW.ano > ultimo THEN
            RAISE EXCEPTION 'O calendário anual já atingiu o último ano suportado (9999).'
                USING ERRCODE = '23514', CONSTRAINT = 'ck_exercicio_ordem_calendario';
        END IF;
        RAISE EXCEPTION 'Ano % fora da sequência do calendário. Último exercício cadastrado: %.', NEW.ano, ultimo
            USING ERRCODE = '23514', CONSTRAINT = 'ck_exercicio_ordem_calendario';
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER trg_proteger_ordem_exercicios ON exercicio_contabil;
CREATE TRIGGER trg_proteger_ordem_exercicios
BEFORE INSERT OR UPDATE OR DELETE ON exercicio_contabil FOR EACH ROW EXECUTE FUNCTION proteger_ordem_exercicios();
