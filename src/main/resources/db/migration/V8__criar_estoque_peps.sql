CREATE TABLE produto_estoque (
    id UUID PRIMARY KEY,
    empresa_id UUID NOT NULL REFERENCES empresa (id),
    codigo VARCHAR(50) NOT NULL CHECK (btrim(codigo) <> ''),
    descricao VARCHAR(200) NOT NULL CHECK (btrim(descricao) <> ''),
    unidade VARCHAR(16) NOT NULL CHECK (btrim(unidade) <> ''),
    ativo BOOLEAN NOT NULL,
    UNIQUE (empresa_id, codigo),
    UNIQUE (empresa_id, id)
);

CREATE TABLE movimento_estoque (
    id UUID PRIMARY KEY,
    ordem BIGINT GENERATED ALWAYS AS IDENTITY UNIQUE,
    empresa_id UUID NOT NULL,
    produto_id UUID NOT NULL,
    exercicio_id UUID NOT NULL,
    periodo_id UUID NOT NULL,
    usuario_id UUID NOT NULL REFERENCES usuario (id),
    data DATE NOT NULL,
    tipo VARCHAR(7) NOT NULL CHECK (tipo IN ('ENTRADA', 'SAIDA')),
    quantidade NUMERIC(19,6) NOT NULL CHECK (quantidade > 0 AND quantidade <> 'NaN'::numeric),
    valor_total NUMERIC NOT NULL CHECK (valor_total >= 0 AND valor_total < 'Infinity'::numeric AND scale(valor_total) <= 2),
    lancamento_id UUID,
    FOREIGN KEY (empresa_id, produto_id) REFERENCES produto_estoque (empresa_id, id),
    FOREIGN KEY (periodo_id, empresa_id, exercicio_id) REFERENCES periodo_contabil (id, empresa_id, exercicio_id),
    FOREIGN KEY (lancamento_id, empresa_id) REFERENCES lancamento_contabil (id, empresa_id),
    UNIQUE (empresa_id, produto_id, id, tipo),
    CHECK (tipo <> 'ENTRADA' OR valor_total > 0)
);

CREATE INDEX ix_movimento_estoque_produto_ordem
    ON movimento_estoque (empresa_id, produto_id, data, ordem);

CREATE TABLE lote_estoque (
    entrada_id UUID PRIMARY KEY,
    empresa_id UUID NOT NULL,
    produto_id UUID NOT NULL,
    tipo_entrada VARCHAR(7) NOT NULL DEFAULT 'ENTRADA' CHECK (tipo_entrada = 'ENTRADA'),
    custo_unitario NUMERIC(19,8) NOT NULL CHECK (custo_unitario > 0 AND custo_unitario <> 'NaN'::numeric),
    quantidade_restante NUMERIC(19,6) NOT NULL CHECK (quantidade_restante >= 0 AND quantidade_restante <> 'NaN'::numeric),
    valor_restante NUMERIC NOT NULL CHECK (valor_restante >= 0 AND valor_restante < 'Infinity'::numeric AND scale(valor_restante) <= 2),
    CHECK (quantidade_restante <> 0 OR valor_restante = 0),
    FOREIGN KEY (empresa_id, produto_id, entrada_id, tipo_entrada)
        REFERENCES movimento_estoque (empresa_id, produto_id, id, tipo),
    UNIQUE (empresa_id, produto_id, entrada_id)
);

CREATE TABLE alocacao_estoque (
    saida_id UUID NOT NULL,
    entrada_id UUID NOT NULL,
    empresa_id UUID NOT NULL,
    produto_id UUID NOT NULL,
    tipo_saida VARCHAR(7) NOT NULL DEFAULT 'SAIDA' CHECK (tipo_saida = 'SAIDA'),
    quantidade NUMERIC(19,6) NOT NULL CHECK (quantidade > 0 AND quantidade <> 'NaN'::numeric),
    valor NUMERIC NOT NULL CHECK (valor >= 0 AND valor < 'Infinity'::numeric AND scale(valor) <= 2),
    PRIMARY KEY (saida_id, entrada_id),
    FOREIGN KEY (empresa_id, produto_id, saida_id, tipo_saida)
        REFERENCES movimento_estoque (empresa_id, produto_id, id, tipo),
    FOREIGN KEY (empresa_id, produto_id, entrada_id)
        REFERENCES lote_estoque (empresa_id, produto_id, entrada_id)
);

-- A movimentação pode preceder o lançamento dentro da mesma transação.
-- Consulte a linha atual porque o evento de INSERT ainda contém o vínculo nulo.
CREATE FUNCTION exigir_contabilizacao_estoque() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM movimento_estoque m
        WHERE m.id = NEW.id AND NOT EXISTS (
            SELECT 1 FROM lancamento_contabil l
            WHERE l.id = m.lancamento_id AND l.empresa_id = m.empresa_id
              AND l.exercicio_id = m.exercicio_id AND l.periodo_id = m.periodo_id AND l.data = m.data
              AND l.origem = 'ESTOQUE' AND l.status = 'CONFIRMADO'
        )
    ) THEN
        RAISE EXCEPTION 'Movimento de estoque exige contabilização confirmada na mesma empresa e data.'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_movimento_estoque_contabilizado';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_exigir_contabilizacao_estoque
AFTER INSERT OR UPDATE ON movimento_estoque
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
EXECUTE FUNCTION exigir_contabilizacao_estoque();
