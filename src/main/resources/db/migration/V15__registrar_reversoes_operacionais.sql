-- Estoque e aquisição compartilham a referência única e a data contábil do estorno da V5.
CREATE VIEW estorno_operacional_efetivo AS
SELECT o.empresa_id, o.id AS original_id, e.id AS lancamento_id, e.data, e.numero,
       e.confirmado_por AS usuario_id
FROM lancamento_contabil o
JOIN lancamento_contabil e ON e.estorno_de_id = o.id AND e.empresa_id = o.empresa_id
    AND e.exercicio_id = o.exercicio_id AND e.data >= o.data
WHERE o.origem IN ('ESTOQUE', 'ATIVO', 'DEPRECIACAO') AND o.status = 'ESTORNADO'
  AND e.origem = 'ESTORNO' AND e.status = 'CONFIRMADO';

-- A competência pode ser apropriada novamente, preservando todas as versões e suas partidas.
ALTER TABLE apropriacao_depreciacao
    ADD COLUMN data_estorno DATE,
    ADD COLUMN estornado_por UUID REFERENCES usuario (id),
    ADD COLUMN lancamento_estorno_id UUID UNIQUE,
    ADD CONSTRAINT fk_depreciacao_estorno FOREIGN KEY (lancamento_estorno_id, empresa_id)
        REFERENCES lancamento_contabil (id, empresa_id),
    ADD CONSTRAINT ck_depreciacao_dados_estorno CHECK (
        (data_estorno IS NULL AND estornado_por IS NULL AND lancamento_estorno_id IS NULL)
        OR (data_estorno IS NOT NULL AND estornado_por IS NOT NULL
            AND data_estorno = (competencia + interval '1 month - 1 day')::date
            AND ((valor > 0) = (lancamento_estorno_id IS NOT NULL)))
    ),
    DROP CONSTRAINT uq_depreciacao_ativo_competencia;

CREATE UNIQUE INDEX uq_depreciacao_ativo_competencia
    ON apropriacao_depreciacao (empresa_id, ativo_id, competencia) WHERE data_estorno IS NULL;

CREATE OR REPLACE FUNCTION validar_apropriacao_depreciacao() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND (OLD.data_estorno IS NOT NULL OR NEW.data_estorno IS NOT NULL) AND (
        OLD.data_estorno IS NOT NULL OR
        (to_jsonb(OLD) - ARRAY['data_estorno', 'estornado_por', 'lancamento_estorno_id']) <>
        (to_jsonb(NEW) - ARRAY['data_estorno', 'estornado_por', 'lancamento_estorno_id'])
    ) THEN
        RAISE EXCEPTION 'Os dados originais de apropriações estornadas são imutáveis.'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_depreciacao_historico_estorno';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM ativo_patrimonial a
        JOIN periodo_contabil p ON p.id = NEW.periodo_id AND p.empresa_id = NEW.empresa_id
        LEFT JOIN lancamento_contabil l ON l.id = NEW.lancamento_id AND l.empresa_id = NEW.empresa_id
        WHERE a.empresa_id = NEW.empresa_id AND a.id = NEW.ativo_id
          AND a.depreciavel AND a.custo_aquisicao > a.valor_residual
          AND a.data_disponibilidade <= p.data_fim AND NEW.competencia = p.data_inicio
          AND (NEW.data_estorno IS NULL OR p.status = 'ABERTO')
          AND (NEW.valor = 0 OR (
              l.origem = 'DEPRECIACAO'
              AND l.exercicio_id = NEW.exercicio_id AND l.periodo_id = p.id AND l.data = p.data_fim
              AND l.confirmado_por = NEW.usuario_id
              AND ((NEW.data_estorno IS NULL AND l.status = 'CONFIRMADO') OR (
                  NEW.data_estorno IS NOT NULL AND EXISTS (
                      SELECT 1 FROM estorno_operacional_efetivo e
                      WHERE e.original_id = l.id AND e.lancamento_id = NEW.lancamento_estorno_id
                        AND e.data = NEW.data_estorno AND e.usuario_id = NEW.estornado_por
                  )
              ))
          ))
    ) THEN
        RAISE EXCEPTION 'Apropriação de depreciação incompatível com o ativo, a competência ou o lançamento.'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_depreciacao_contabilizada';
    END IF;
    RETURN NEW;
END;
$$;

-- Aceita a confirmação e o estorno da operação na mesma transação.
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
          AND l.data = movimento.data
          AND (l.status = 'CONFIRMADO' OR EXISTS (
              SELECT 1 FROM estorno_operacional_efetivo e WHERE e.original_id = l.id
          ))
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
              AND l.origem = 'ATIVO'
              AND (l.status = 'CONFIRMADO' OR EXISTS (
                  SELECT 1 FROM estorno_operacional_efetivo e WHERE e.original_id = l.id
              ))
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
