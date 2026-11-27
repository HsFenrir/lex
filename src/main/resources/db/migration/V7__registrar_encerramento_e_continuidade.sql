ALTER TABLE lancamento_contabil
    DROP CONSTRAINT ck_lancamento_origem,
    ADD CONSTRAINT ck_lancamento_origem CHECK (
        origem IN ('MANUAL', 'ESTOQUE', 'ATIVO', 'DEPRECIACAO', 'ESTORNO', 'ABERTURA', 'ENCERRAMENTO')
    );

CREATE UNIQUE INDEX uq_lancamento_encerramento_exercicio
    ON lancamento_contabil (empresa_id, exercicio_id)
    WHERE origem = 'ENCERRAMENTO';

ALTER TABLE exercicio_contabil
    ADD COLUMN exercicio_origem_id UUID,
    ADD CONSTRAINT fk_exercicio_continuidade_empresa
        FOREIGN KEY (exercicio_origem_id, empresa_id)
        REFERENCES exercicio_contabil (id, empresa_id) ON DELETE RESTRICT,
    ADD CONSTRAINT uq_exercicio_continuidade_origem UNIQUE (exercicio_origem_id),
    ADD CONSTRAINT ck_exercicio_continuidade_distinta CHECK (exercicio_origem_id <> id);
