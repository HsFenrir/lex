-- Preserva a validação da V5 e amplia somente a precisão dos totais agregados.
CREATE OR REPLACE FUNCTION validar_transicao_lancamento()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    v_periodo periodo_contabil%ROWTYPE;
    v_exercicio exercicio_contabil%ROWTYPE;
    v_empresa_ativa BOOLEAN;
    v_quantidade BIGINT;
    v_debitos NUMERIC;
    v_creditos NUMERIC;
    v_contas_invalidas BIGINT;
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.status <> 'RASCUNHO' THEN
            RAISE EXCEPTION 'Lançamentos confirmados ou estornados não podem ser excluídos.';
        END IF;
        RETURN OLD;
    END IF;

    IF OLD.status IN ('CONFIRMADO', 'ESTORNADO') THEN
        IF NOT (OLD.status = 'CONFIRMADO' AND NEW.status = 'ESTORNADO'
                AND (to_jsonb(NEW) - 'status') = (to_jsonb(OLD) - 'status')) THEN
            RAISE EXCEPTION 'Lançamentos confirmados são imutáveis; utilize o procedimento de estorno.';
        END IF;

        IF NOT EXISTS (
            SELECT 1
              FROM lancamento_contabil e
             WHERE e.estorno_de_id = OLD.id
               AND e.empresa_id = OLD.empresa_id
               AND e.status = 'CONFIRMADO'
               AND e.origem = 'ESTORNO'
        ) THEN
            RAISE EXCEPTION 'O lançamento só pode ser marcado como estornado após a confirmação do lançamento de estorno correspondente.';
        END IF;
        RETURN NEW;
    END IF;

    IF OLD.status = 'RASCUNHO' AND NEW.status = 'RASCUNHO' THEN
        RETURN NEW;
    END IF;

    IF OLD.status <> 'RASCUNHO' OR NEW.status <> 'CONFIRMADO' THEN
        RAISE EXCEPTION 'Transição de status de lançamento inválida.';
    END IF;

    IF NEW.numero IS NULL OR NEW.confirmado_em IS NULL OR NEW.confirmado_por IS NULL THEN
        RAISE EXCEPTION 'A confirmação exige número, data/hora e usuário responsável.';
    END IF;

    SELECT * INTO v_periodo
      FROM periodo_contabil
     WHERE id = NEW.periodo_id
       AND empresa_id = NEW.empresa_id
       AND exercicio_id = NEW.exercicio_id
     FOR SHARE;

    IF NOT FOUND OR v_periodo.status <> 'ABERTO'
       OR NEW.data < v_periodo.data_inicio OR NEW.data > v_periodo.data_fim THEN
        RAISE EXCEPTION 'O lançamento deve pertencer a um período aberto e sua data deve estar dentro do período.';
    END IF;

    SELECT * INTO v_exercicio
      FROM exercicio_contabil
     WHERE id = NEW.exercicio_id
       AND empresa_id = NEW.empresa_id
     FOR SHARE;

    IF NOT FOUND OR v_exercicio.status <> 'ABERTO' THEN
        RAISE EXCEPTION 'Não é possível confirmar lançamentos em exercício encerrado.';
    END IF;

    SELECT ativa INTO v_empresa_ativa
      FROM empresa
     WHERE id = NEW.empresa_id
     FOR SHARE;

    IF v_empresa_ativa IS DISTINCT FROM TRUE THEN
        RAISE EXCEPTION 'Não é possível confirmar lançamentos para empresa inativa ou arquivada.';
    END IF;

    SELECT COUNT(*),
           COALESCE(SUM(valor) FILTER (WHERE tipo = 'DEBITO'), 0),
           COALESCE(SUM(valor) FILTER (WHERE tipo = 'CREDITO'), 0)
      INTO v_quantidade, v_debitos, v_creditos
      FROM partida_contabil
     WHERE lancamento_id = NEW.id;

    IF v_quantidade < 2 THEN
        RAISE EXCEPTION 'Um lançamento contábil deve possuir pelo menos duas partidas.';
    END IF;

    IF v_debitos <> v_creditos THEN
        RAISE EXCEPTION 'Lançamento desbalanceado: débitos (%) diferentes de créditos (%).', v_debitos, v_creditos;
    END IF;

    SELECT COUNT(*) INTO v_contas_invalidas
      FROM partida_contabil p
      JOIN conta_contabil c
        ON c.id = p.conta_contabil_id
       AND c.plano_contas_id = p.plano_contas_id
     WHERE p.lancamento_id = NEW.id
       AND (c.ativa IS NOT TRUE OR c.tipo <> 'ANALITICA');

    IF v_contas_invalidas > 0 THEN
        RAISE EXCEPTION 'Todas as partidas devem utilizar contas analíticas ativas do plano de contas do lançamento.';
    END IF;

    RETURN NEW;
END;
$$;
