package br.com.lex.domain.contabil.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "ativo_patrimonial")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AtivoPatrimonial {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(name = "empresa_id", nullable = false)
    private UUID empresaId;
    @Column(name = "plano_contas_id", nullable = false)
    private UUID planoContasId;
    @Column(name = "usuario_id", nullable = false)
    private UUID usuarioId;
    @Column(nullable = false, length = 50)
    private String codigo;
    @Column(nullable = false, length = 200)
    private String descricao;
    @Column(name = "conta_patrimonial_id", nullable = false)
    private UUID contaPatrimonialId;
    @Column(name = "conta_redutora_id")
    private UUID contaRedutoraId;
    @Column(name = "conta_despesa_id")
    private UUID contaDespesaId;
    @Column(name = "data_aquisicao", nullable = false)
    private LocalDate dataAquisicao;
    @Column(name = "data_disponibilidade")
    private LocalDate dataDisponibilidade;
    @Column(name = "custo_aquisicao", nullable = false, precision = 19, scale = 2)
    private BigDecimal custoAquisicao;
    @Column(nullable = false)
    private boolean depreciavel;
    @Column(name = "valor_residual", nullable = false, precision = 19, scale = 2)
    private BigDecimal valorResidual;
    @Column(name = "vida_util_meses")
    private Integer vidaUtilMeses;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lancamento_aquisicao_id")
    private LancamentoContabil lancamentoAquisicao;

    public AtivoPatrimonial(UUID empresaId, UUID planoContasId, UUID usuarioId, String codigo, String descricao,
                            UUID contaPatrimonialId, UUID contaRedutoraId, UUID contaDespesaId,
                            LocalDate dataAquisicao, LocalDate dataDisponibilidade, BigDecimal custoAquisicao,
                            boolean depreciavel, BigDecimal valorResidual, Integer vidaUtilMeses) {
        this.empresaId = empresaId;
        this.planoContasId = planoContasId;
        this.usuarioId = usuarioId;
        this.codigo = codigo;
        this.descricao = descricao;
        this.contaPatrimonialId = contaPatrimonialId;
        this.contaRedutoraId = contaRedutoraId;
        this.contaDespesaId = contaDespesaId;
        this.dataAquisicao = dataAquisicao;
        this.dataDisponibilidade = dataDisponibilidade;
        this.custoAquisicao = custoAquisicao;
        this.depreciavel = depreciavel;
        this.valorResidual = valorResidual;
        this.vidaUtilMeses = vidaUtilMeses;
    }

    public void vincularAquisicao(LancamentoContabil lancamento) {
        if (lancamentoAquisicao != null) {
            throw new IllegalStateException("O ativo já possui lançamento de aquisição.");
        }
        lancamentoAquisicao = lancamento;
    }

    public BigDecimal baseDepreciavel() {
        return depreciavel ? custoAquisicao.subtract(valorResidual) : BigDecimal.ZERO.setScale(2);
    }

    public void registrarDisponibilidade(LocalDate data) {
        if (dataDisponibilidade != null) {
            throw new IllegalStateException("A disponibilidade para uso já foi registrada.");
        }
        if (data == null || data.isBefore(dataAquisicao)) {
            throw new IllegalArgumentException("A disponibilidade para uso não pode anteceder a aquisição.");
        }
        dataDisponibilidade = data;
    }
}
