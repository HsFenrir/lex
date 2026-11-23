
package br.com.lex.domain.contabil.entity;

import br.com.lex.domain.contabil.enums.OrigemLancamento;
import br.com.lex.domain.contabil.enums.StatusLancamento;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Entity
@Table(name = "lancamento_contabil")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LancamentoContabil {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "empresa_id", nullable = false)
    private UUID empresaId;

    @Column(name = "exercicio_id", nullable = false)
    private UUID exercicioId;

    @Column(name = "periodo_id", nullable = false)
    private UUID periodoId;

    @Column(name = "plano_contas_id", nullable = false)
    private UUID planoContasId;

    @Column(name = "data", nullable = false)
    private LocalDate data;

    @Column(name = "numero")
    private Long numero;

    @Column(name = "historico", nullable = false, columnDefinition = "TEXT")
    private String historico;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 12)
    private StatusLancamento status = StatusLancamento.RASCUNHO;

    @Enumerated(EnumType.STRING)
    @Column(name = "origem", nullable = false, length = 20)
    private OrigemLancamento origem = OrigemLancamento.MANUAL;

    @Column(
            name = "criado_em",
            nullable = false,
            insertable = false,
            updatable = false
    )
    private OffsetDateTime criadoEm;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "criado_por")
    private Usuario criadoPor;

    @Column(name = "confirmado_em")
    private OffsetDateTime confirmadoEm;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "confirmado_por")
    private Usuario confirmadoPor;

    @Column(name = "estorno_de_id")
    private UUID estornoDeId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "empresa_id",
            referencedColumnName = "id",
            insertable = false,
            updatable = false
    )
    private Empresa empresa;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumns({
            @JoinColumn(
                    name = "exercicio_id",
                    referencedColumnName = "id",
                    insertable = false,
                    updatable = false
            ),
            @JoinColumn(
                    name = "empresa_id",
                    referencedColumnName = "empresa_id",
                    insertable = false,
                    updatable = false
            )
    })
    private ExercicioContabil exercicio;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumns({
            @JoinColumn(
                    name = "periodo_id",
                    referencedColumnName = "id",
                    insertable = false,
                    updatable = false
            ),
            @JoinColumn(
                    name = "empresa_id",
                    referencedColumnName = "empresa_id",
                    insertable = false,
                    updatable = false
            ),
            @JoinColumn(
                    name = "exercicio_id",
                    referencedColumnName = "exercicio_id",
                    insertable = false,
                    updatable = false
            )
    })
    private PeriodoContabil periodo;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumns({
            @JoinColumn(
                    name = "plano_contas_id",
                    referencedColumnName = "id",
                    insertable = false,
                    updatable = false
            ),
            @JoinColumn(
                    name = "empresa_id",
                    referencedColumnName = "empresa_id",
                    insertable = false,
                    updatable = false
            )
    })
    private PlanoContas planoContas;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumns({
            @JoinColumn(
                    name = "estorno_de_id",
                    referencedColumnName = "id",
                    insertable = false,
                    updatable = false
            ),
            @JoinColumn(
                    name = "empresa_id",
                    referencedColumnName = "empresa_id",
                    insertable = false,
                    updatable = false
            )
    })
    private LancamentoContabil estornoDe;

    public LancamentoContabil(
            Empresa empresa,
            ExercicioContabil exercicio,
            PeriodoContabil periodo,
            PlanoContas planoContas,
            Usuario criadoPor,
            LocalDate data,
            String historico,
            OrigemLancamento origem
    ) {
        if (empresa == null || empresa.getId() == null) {
            throw new IllegalArgumentException(
                    "A empresa deve estar persistida."
            );
        }

        if (exercicio == null || exercicio.getId() == null) {
            throw new IllegalArgumentException(
                    "O exercício deve estar persistido."
            );
        }

        if (periodo == null || periodo.getId() == null) {
            throw new IllegalArgumentException(
                    "O período deve estar persistido."
            );
        }

        if (planoContas == null || planoContas.getId() == null) {
            throw new IllegalArgumentException(
                    "O plano de contas deve estar persistido."
            );
        }

        if (!empresa.getId().equals(exercicio.getEmpresa().getId())) {
            throw new IllegalArgumentException(
                    "O exercício não pertence à empresa informada."
            );
        }

        if (!empresa.getId().equals(periodo.getEmpresaId())
                || !exercicio.getId().equals(periodo.getExercicioId())) {
            throw new IllegalArgumentException(
                    "O período não pertence ao exercício e à empresa informados."
            );
        }

        if (!empresa.getId().equals(planoContas.getEmpresaId())) {
            throw new IllegalArgumentException(
                    "O plano de contas não pertence à empresa informada."
            );
        }

        if (data == null) {
            throw new IllegalArgumentException(
                    "A data do lançamento é obrigatória."
            );
        }

        if (historico == null || historico.isBlank()) {
            throw new IllegalArgumentException(
                    "O histórico do lançamento é obrigatório."
            );
        }

        if (origem == null) {
            throw new IllegalArgumentException(
                    "A origem do lançamento é obrigatória."
            );
        }

        this.empresaId = empresa.getId();
        this.exercicioId = exercicio.getId();
        this.periodoId = periodo.getId();
        this.planoContasId = planoContas.getId();

        this.empresa = empresa;
        this.exercicio = exercicio;
        this.periodo = periodo;
        this.planoContas = planoContas;

        this.criadoPor = criadoPor;
        this.data = data;
        this.historico = historico.trim();
        this.origem = origem;
        this.status = StatusLancamento.RASCUNHO;
    }

    public static LancamentoContabil criarEstorno(
            LancamentoContabil original,
            Usuario criadoPor,
            LocalDate data,
            String historico
    ) {
        if (original == null || original.getId() == null) {
            throw new IllegalArgumentException(
                    "O lançamento original deve estar persistido."
            );
        }

        LancamentoContabil estorno = new LancamentoContabil(
                original.getEmpresa(),
                original.getExercicio(),
                original.getPeriodo(),
                original.getPlanoContas(),
                criadoPor,
                data,
                historico,
                OrigemLancamento.ESTORNO
        );

        estorno.estornoDeId = original.getId();
        estorno.estornoDe = original;

        return estorno;
    }

    public void confirmar(
            Long numero,
            Usuario confirmadoPor,
            OffsetDateTime confirmadoEm
    ) {
        if (this.status != StatusLancamento.RASCUNHO) {
            throw new IllegalStateException(
                    "Somente lançamentos em rascunho podem ser confirmados."
            );
        }

        if (numero == null || numero <= 0) {
            throw new IllegalArgumentException(
                    "O número do lançamento deve ser positivo."
            );
        }

        if (confirmadoPor == null || confirmadoPor.getId() == null) {
            throw new IllegalArgumentException(
                    "O usuário responsável pela confirmação é obrigatório."
            );
        }

        if (confirmadoEm == null) {
            throw new IllegalArgumentException(
                    "A data e hora da confirmação são obrigatórias."
            );
        }

        this.numero = numero;
        this.confirmadoPor = confirmadoPor;
        this.confirmadoEm = confirmadoEm;
        this.status = StatusLancamento.CONFIRMADO;
    }

    public void marcarComoEstornado() {
        if (this.status != StatusLancamento.CONFIRMADO) {
            throw new IllegalStateException(
                    "Somente lançamentos confirmados podem ser estornados."
            );
        }

        this.status = StatusLancamento.ESTORNADO;
    }
}
