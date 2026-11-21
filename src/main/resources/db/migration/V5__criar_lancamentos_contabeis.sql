-- V5 - Estrutura de lançamentos e partidas contábeis
-- PostgreSQL / Flyway
-- Regras centrais:
-- 1. Rascunhos não consomem numeração.
-- 2. A numeração é sequencial por empresa e exercício, atribuída na confirmação.
-- 3. Lançamentos confirmados são imutáveis; correções são feitas por estorno.
-- 4. O estorno é registrado em período aberto e pode referenciar exercício anterior.

-- Chaves candidatas adicionais necessárias para FKs compostas multiempresa.
ALTER TABLE plano_contas
    ADD CONSTRAINT uq_plano_contas_id_empresa UNIQUE (id, empresa_id);

ALTER TABLE exercicio_contabil
    ADD CONSTRAINT uq_exercicio_id_empresa UNIQUE (id, empresa_id);

ALTER TABLE periodo_contabil
    ADD CONSTRAINT uq_periodo_id_empresa_exercicio UNIQUE (id, empresa_id, exercicio_id);

-- Contador transacional. O serviço incrementará esta linha na mesma transação
-- em que confirmar o lançamento, usando INSERT ... ON CONFLICT ... RETURNING.
CREATE TABLE sequencia_lancamento (
    empresa_id UUID NOT NULL,
    exercicio_id UUID NOT NULL,
    ultimo_numero BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT pk_sequencia_lancamento PRIMARY KEY (empresa_id, exercicio_id),
    CONSTRAINT fk_sequencia_lancamento_exercicio
        FOREIGN KEY (exercicio_id, empresa_id)
        REFERENCES exercicio_contabil (id, empresa_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_sequencia_lancamento_numero CHECK (ultimo_numero >= 0)
);

CREATE TABLE lancamento_contabil (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    empresa_id UUID NOT NULL,
    exercicio_id UUID NOT NULL,
    periodo_id UUID NOT NULL,
    plano_contas_id UUID NOT NULL,
    data DATE NOT NULL,
    numero BIGINT,
    historico TEXT NOT NULL,
    status VARCHAR(12) NOT NULL DEFAULT 'RASCUNHO',
    origem VARCHAR(20) NOT NULL DEFAULT 'MANUAL',
    criado_em TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    criado_por UUID,
    confirmado_em TIMESTAMPTZ,
    confirmado_por UUID,
    estorno_de_id UUID,

    CONSTRAINT ck_lancamento_historico_nao_vazio
        CHECK (LENGTH(BTRIM(historico)) > 0),
    CONSTRAINT ck_lancamento_status
        CHECK (status IN ('RASCUNHO', 'CONFIRMADO', 'ESTORNADO')),
    CONSTRAINT ck_lancamento_origem
        CHECK (origem IN ('MANUAL', 'ESTOQUE', 'ATIVO', 'DEPRECIACAO', 'ESTORNO', 'ABERTURA')),
    CONSTRAINT ck_lancamento_numero_positivo
        CHECK (numero IS NULL OR numero > 0),
    CONSTRAINT ck_lancamento_confirmacao
        CHECK (
            (status = 'RASCUNHO' AND numero IS NULL AND confirmado_em IS NULL AND confirmado_por IS NULL)
            OR
            (status IN ('CONFIRMADO', 'ESTORNADO') AND numero IS NOT NULL
                AND confirmado_em IS NOT NULL AND confirmado_por IS NOT NULL)
        ),
    CONSTRAINT ck_lancamento_estorno_origem
        CHECK ((origem = 'ESTORNO') = (estorno_de_id IS NOT NULL)),
    CONSTRAINT ck_lancamento_nao_estornar_a_si_proprio
        CHECK (estorno_de_id IS NULL OR estorno_de_id <> id),

    CONSTRAINT uq_lancamento_id_empresa UNIQUE (id, empresa_id),
    CONSTRAINT uq_lancamento_id_plano UNIQUE (id, plano_contas_id),
    CONSTRAINT uq_lancamento_numero_empresa_exercicio
        UNIQUE (empresa_id, exercicio_id, numero),

    CONSTRAINT fk_lancamento_empresa
        FOREIGN KEY (empresa_id) REFERENCES empresa (id) ON DELETE RESTRICT,
    CONSTRAINT fk_lancamento_exercicio_empresa
        FOREIGN KEY (exercicio_id, empresa_id)
        REFERENCES exercicio_contabil (id, empresa_id) ON DELETE RESTRICT,
    CONSTRAINT fk_lancamento_periodo_empresa_exercicio
        FOREIGN KEY (periodo_id, empresa_id, exercicio_id)
        REFERENCES periodo_contabil (id, empresa_id, exercicio_id) ON DELETE RESTRICT,
    CONSTRAINT fk_lancamento_plano_empresa
        FOREIGN KEY (plano_contas_id, empresa_id)
        REFERENCES plano_contas (id, empresa_id) ON DELETE RESTRICT,
    CONSTRAINT fk_lancamento_criado_por
        FOREIGN KEY (criado_por) REFERENCES usuario (id) ON DELETE SET NULL,
    CONSTRAINT fk_lancamento_confirmado_por
        FOREIGN KEY (confirmado_por) REFERENCES usuario (id) ON DELETE RESTRICT,
    CONSTRAINT fk_lancamento_estorno_original_empresa
        FOREIGN KEY (estorno_de_id, empresa_id)
        REFERENCES lancamento_contabil (id, empresa_id) ON DELETE RESTRICT
);

-- Um lançamento original pode ter, no máximo, um estorno.
CREATE UNIQUE INDEX uq_lancamento_estorno_unico
    ON lancamento_contabil (estorno_de_id)
    WHERE estorno_de_id IS NOT NULL;

CREATE INDEX ix_lancamento_empresa_data
    ON lancamento_contabil (empresa_id, data);

CREATE INDEX ix_lancamento_periodo_status
    ON lancamento_contabil (periodo_id, status);

CREATE INDEX ix_lancamento_plano
    ON lancamento_contabil (plano_contas_id);

CREATE TABLE partida_contabil (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    lancamento_id UUID NOT NULL,
    plano_contas_id UUID NOT NULL,
    conta_contabil_id UUID NOT NULL,
    tipo VARCHAR(8) NOT NULL,
    valor NUMERIC(19,2) NOT NULL,
    historico TEXT,

    CONSTRAINT ck_partida_tipo CHECK (tipo IN ('DEBITO', 'CREDITO')),
    CONSTRAINT ck_partida_valor_positivo CHECK (valor > 0),
    CONSTRAINT ck_partida_historico_nao_vazio
        CHECK (historico IS NULL OR LENGTH(BTRIM(historico)) > 0),

    CONSTRAINT fk_partida_lancamento_plano
        FOREIGN KEY (lancamento_id, plano_contas_id)
        REFERENCES lancamento_contabil (id, plano_contas_id) ON DELETE RESTRICT,
    CONSTRAINT fk_partida_conta_mesmo_plano
        FOREIGN KEY (plano_contas_id, conta_contabil_id)
        REFERENCES conta_contabil (plano_contas_id, id) ON DELETE RESTRICT
);

CREATE INDEX ix_partida_lancamento ON partida_contabil (lancamento_id);
CREATE INDEX ix_partida_conta ON partida_contabil (conta_contabil_id);

-- Protege a inclusão/alteração/exclusão de partidas contra concorrência com
-- a confirmação do cabeçalho. O bloqueio da linha-pai serializa as operações.
CREATE OR REPLACE FUNCTION proteger_partida_lancamento_rascunho()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    v_status VARCHAR(12);
BEGIN
    IF TG_OP = 'UPDATE' AND NEW.lancamento_id <> OLD.lancamento_id THEN
        RAISE EXCEPTION 'Não é permitido transferir uma partida entre lançamentos.';
    END IF;

    SELECT status
      INTO v_status
      FROM lancamento_contabil
     WHERE id = CASE WHEN TG_OP = 'DELETE' THEN OLD.lancamento_id ELSE NEW.lancamento_id END
     FOR UPDATE;

    IF v_status IS DISTINCT FROM 'RASCUNHO' THEN
        RAISE EXCEPTION 'Partidas só podem ser incluídas, alteradas ou excluídas enquanto o lançamento estiver em rascunho.';
    END IF;

    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_partida_somente_em_rascunho
BEFORE INSERT OR UPDATE OR DELETE ON partida_contabil
FOR EACH ROW EXECUTE FUNCTION proteger_partida_lancamento_rascunho();

-- Valida a confirmação no banco, além das validações obrigatórias no serviço.
-- A aplicação ainda deve verificar autorização do usuário e registrar auditoria.
CREATE OR REPLACE FUNCTION validar_transicao_lancamento()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    v_periodo periodo_contabil%ROWTYPE;
    v_exercicio exercicio_contabil%ROWTYPE;
    v_empresa_ativa BOOLEAN;
    v_quantidade BIGINT;
    v_debitos NUMERIC(19,2);
    v_creditos NUMERIC(19,2);
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

CREATE TRIGGER trg_validar_transicao_lancamento
BEFORE UPDATE OR DELETE ON lancamento_contabil
FOR EACH ROW EXECUTE FUNCTION validar_transicao_lancamento();

-- Bloqueia exclusão de rascunhos que ainda tenham partidas? A FK RESTRICT já
-- impede a exclusão enquanto existirem partidas. Para excluir, o serviço deve
-- remover as partidas primeiro, dentro da mesma transação.

COMMENT ON TABLE lancamento_contabil IS
'Cabeçalho do lançamento contábil. Rascunhos não possuem número; a numeração é atribuída na confirmação.';
COMMENT ON TABLE partida_contabil IS
'Partidas de débito e crédito. A confirmação exige ao menos duas partidas e igualdade entre débitos e créditos.';
COMMENT ON TABLE sequencia_lancamento IS
'Contador de numeração sequencial por empresa e exercício, incrementado de forma transacional na confirmação.';
