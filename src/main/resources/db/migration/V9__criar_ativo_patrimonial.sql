CREATE TABLE ativo_patrimonial (
    id UUID PRIMARY KEY,
    empresa_id UUID NOT NULL,
    plano_contas_id UUID NOT NULL,
    usuario_id UUID NOT NULL REFERENCES usuario (id),
    codigo VARCHAR(50) NOT NULL CHECK (btrim(codigo) <> '' AND codigo = btrim(codigo)),
    descricao VARCHAR(200) NOT NULL CHECK (btrim(descricao) <> ''),
    conta_patrimonial_id UUID NOT NULL,
    conta_redutora_id UUID,
    conta_despesa_id UUID,
    data_aquisicao DATE NOT NULL,
    data_disponibilidade DATE,
    custo_aquisicao NUMERIC(19,2) NOT NULL,
    depreciavel BOOLEAN NOT NULL,
    valor_residual NUMERIC(19,2) NOT NULL,
    vida_util_meses INTEGER,
    lancamento_aquisicao_id UUID,
    CONSTRAINT uq_ativo_patrimonial_codigo UNIQUE (empresa_id, codigo),
    CONSTRAINT uq_ativo_patrimonial_empresa_id UNIQUE (empresa_id, id),
    CONSTRAINT uq_ativo_patrimonial_lancamento UNIQUE (lancamento_aquisicao_id),
    CONSTRAINT fk_ativo_patrimonial_plano FOREIGN KEY (plano_contas_id, empresa_id)
        REFERENCES plano_contas (id, empresa_id),
    CONSTRAINT fk_ativo_patrimonial_conta FOREIGN KEY (plano_contas_id, conta_patrimonial_id)
        REFERENCES conta_contabil (plano_contas_id, id),
    CONSTRAINT fk_ativo_patrimonial_redutora FOREIGN KEY (plano_contas_id, conta_redutora_id)
        REFERENCES conta_contabil (plano_contas_id, id),
    CONSTRAINT fk_ativo_patrimonial_despesa FOREIGN KEY (plano_contas_id, conta_despesa_id)
        REFERENCES conta_contabil (plano_contas_id, id),
    CONSTRAINT fk_ativo_patrimonial_lancamento FOREIGN KEY (lancamento_aquisicao_id, plano_contas_id)
        REFERENCES lancamento_contabil (id, plano_contas_id),
    CONSTRAINT ck_ativo_patrimonial_datas CHECK (
        data_disponibilidade IS NULL OR data_disponibilidade >= data_aquisicao
    ),
    CONSTRAINT ck_ativo_patrimonial_valores CHECK (
        custo_aquisicao > 0 AND custo_aquisicao < 'Infinity'::numeric
        AND valor_residual >= 0 AND valor_residual <= custo_aquisicao
    ),
    CONSTRAINT ck_ativo_patrimonial_depreciacao CHECK (
        (depreciavel AND vida_util_meses IS NOT NULL AND vida_util_meses > 0
            AND conta_redutora_id IS NOT NULL AND conta_despesa_id IS NOT NULL
            AND conta_redutora_id <> conta_patrimonial_id AND conta_despesa_id <> conta_patrimonial_id
            AND conta_redutora_id <> conta_despesa_id)
        OR (NOT depreciavel AND vida_util_meses IS NULL AND conta_redutora_id IS NULL AND conta_despesa_id IS NULL)
    )
);

-- A aquisição reserva o cadastro antes da escrituração. No commit, confira o vínculo final.
CREATE FUNCTION exigir_aquisicao_ativo_contabilizada() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM ativo_patrimonial a
        WHERE a.id = NEW.id AND NOT EXISTS (
            SELECT 1 FROM lancamento_contabil l
            WHERE l.id = a.lancamento_aquisicao_id AND l.empresa_id = a.empresa_id
              AND l.plano_contas_id = a.plano_contas_id AND l.data = a.data_aquisicao
              AND l.origem = 'ATIVO' AND l.status = 'CONFIRMADO'
              AND l.confirmado_por = a.usuario_id
        )
    ) THEN
        RAISE EXCEPTION 'Ativo patrimonial exige aquisição contabilizada e confirmada.'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_ativo_patrimonial_contabilizado';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_exigir_aquisicao_ativo_contabilizada
AFTER INSERT OR UPDATE ON ativo_patrimonial
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
EXECUTE FUNCTION exigir_aquisicao_ativo_contabilizada();
