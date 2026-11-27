CREATE TABLE apropriacao_depreciacao (
    id UUID PRIMARY KEY,
    empresa_id UUID NOT NULL,
    ativo_id UUID NOT NULL,
    exercicio_id UUID NOT NULL,
    periodo_id UUID NOT NULL,
    usuario_id UUID NOT NULL REFERENCES usuario (id),
    competencia DATE NOT NULL CHECK (EXTRACT(DAY FROM competencia) = 1),
    valor NUMERIC(19,2) NOT NULL CHECK (valor >= 0 AND valor < 'Infinity'::numeric),
    lancamento_id UUID,
    CONSTRAINT uq_depreciacao_ativo_competencia UNIQUE (empresa_id, ativo_id, competencia),
    CONSTRAINT uq_depreciacao_lancamento UNIQUE (lancamento_id),
    CONSTRAINT fk_depreciacao_ativo FOREIGN KEY (empresa_id, ativo_id)
        REFERENCES ativo_patrimonial (empresa_id, id),
    CONSTRAINT fk_depreciacao_periodo FOREIGN KEY (periodo_id, empresa_id, exercicio_id)
        REFERENCES periodo_contabil (id, empresa_id, exercicio_id),
    CONSTRAINT fk_depreciacao_lancamento FOREIGN KEY (lancamento_id, empresa_id)
        REFERENCES lancamento_contabil (id, empresa_id),
    CONSTRAINT ck_depreciacao_vinculo CHECK ((valor > 0) = (lancamento_id IS NOT NULL))
);

CREATE FUNCTION validar_apropriacao_depreciacao() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM ativo_patrimonial a
        JOIN periodo_contabil p ON p.id = NEW.periodo_id AND p.empresa_id = NEW.empresa_id
        LEFT JOIN lancamento_contabil l ON l.id = NEW.lancamento_id AND l.empresa_id = NEW.empresa_id
        WHERE a.empresa_id = NEW.empresa_id AND a.id = NEW.ativo_id
          AND a.depreciavel AND a.custo_aquisicao > a.valor_residual
          AND a.data_disponibilidade <= p.data_fim AND NEW.competencia = p.data_inicio
          AND (NEW.valor = 0 OR (
              l.origem = 'DEPRECIACAO' AND l.status = 'CONFIRMADO'
              AND l.exercicio_id = NEW.exercicio_id AND l.periodo_id = p.id AND l.data = p.data_fim
              AND l.confirmado_por = NEW.usuario_id
          ))
    ) THEN
        RAISE EXCEPTION 'Apropriação de depreciação incompatível com o ativo, a competência ou o lançamento.'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_depreciacao_contabilizada';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_validar_apropriacao_depreciacao
BEFORE INSERT OR UPDATE ON apropriacao_depreciacao
FOR EACH ROW EXECUTE FUNCTION validar_apropriacao_depreciacao();
