
package br.com.lex.domain.contabil.entity;

import br.com.lex.domain.contabil.enums.TipoPartida;
import br.com.lex.domain.contabil.enums.StatusLancamento;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

@Getter
@Entity
@Table(name = "partida_contabil")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PartidaContabil {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "lancamento_id", nullable = false)
    private UUID lancamentoId;

    @Column(name = "plano_contas_id", nullable = false)
    private UUID planoContasId;

    @Column(name = "conta_contabil_id", nullable = false)
    private UUID contaContabilId;

    @Enumerated(EnumType.STRING)
    @Column(name = "tipo", nullable = false, length = 8)
    private TipoPartida tipo;

    @Column(
            name = "valor",
            nullable = false,
            precision = 19,
            scale = 2
    )
    private BigDecimal valor;

    @Column(name = "historico", columnDefinition = "TEXT")
    private String historico;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumns({
            @JoinColumn(
                    name = "lancamento_id",
                    referencedColumnName = "id",
                    insertable = false,
                    updatable = false
            ),
            @JoinColumn(
                    name = "plano_contas_id",
                    referencedColumnName = "plano_contas_id",
                    insertable = false,
                    updatable = false
            )
    })
    private LancamentoContabil lancamento;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumns({
            @JoinColumn(
                    name = "plano_contas_id",
                    referencedColumnName = "plano_contas_id",
                    insertable = false,
                    updatable = false
            ),
            @JoinColumn(
                    name = "conta_contabil_id",
                    referencedColumnName = "id",
                    insertable = false,
                    updatable = false
            )
    })
    private ContaContabil contaContabil;

    public PartidaContabil(
            LancamentoContabil lancamento,
            ContaContabil contaContabil,
            TipoPartida tipo,
            BigDecimal valor,
            String historico
    ) {
        if (lancamento == null || lancamento.getId() == null) {
            throw new IllegalArgumentException(
                    "O lançamento deve estar persistido."
            );
        }

        if (lancamento.getStatus() != StatusLancamento.RASCUNHO) {
            throw new IllegalStateException(
                    "Não é permitido adicionar partidas a um lançamento que não esteja em rascunho."
            );
        }

        if (contaContabil == null || contaContabil.getId() == null) {
            throw new IllegalArgumentException(
                    "A conta contábil deve estar persistida."
            );
        }

        if (!lancamento.getPlanoContasId().equals(
                contaContabil.getPlanoContasId())) {
            throw new IllegalArgumentException(
                    "A conta contábil não pertence ao plano de contas do lançamento."
            );
        }

        if (tipo == null) {
            throw new IllegalArgumentException(
                    "O tipo da partida é obrigatório."
            );
        }

        if (valor == null || valor.signum() <= 0) {
            throw new IllegalArgumentException(
                    "O valor da partida deve ser positivo."
            );
        }

        try {
            valor = valor.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(
                    "O valor da partida não pode possuir mais de duas casas decimais.",
                    e
            );
        }

        if (historico != null && historico.isBlank()) {
            throw new IllegalArgumentException(
                    "O histórico da partida não pode estar vazio."
            );
        }

        this.lancamentoId = lancamento.getId();
        this.planoContasId = lancamento.getPlanoContasId();
        this.contaContabilId = contaContabil.getId();

        this.lancamento = lancamento;
        this.contaContabil = contaContabil;

        this.tipo = tipo;
        this.valor = valor;
        this.historico = historico == null ? null : historico.trim();
    }
}
