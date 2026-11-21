package br.com.lex.domain.contabil.entity;

import br.com.lex.domain.contabil.enums.ClassificacaoBP;
import br.com.lex.domain.contabil.enums.ClassificacaoDRE;
import br.com.lex.domain.contabil.enums.GrupoConta;
import br.com.lex.domain.contabil.enums.NaturezaConta;
import br.com.lex.domain.contabil.enums.TipoConta;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinColumns;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Entity
@Table(name = "conta_contabil")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ContaContabil {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "plano_contas_id", nullable = false)
    private UUID planoContasId;

    @Column(name = "modelo_versao", nullable = false)
    private Integer modeloVersao;

    @Column(name = "modelo_conta_id", nullable = false)
    private UUID modeloContaId;

    @Column(name = "conta_pai_id")
    private UUID contaPaiId;

    @Column(name = "codigo", nullable = false, length = 30)
    private String codigo;

    @Column(name = "nome", nullable = false, length = 200)
    private String nome;

    @Enumerated(EnumType.STRING)
    @Column(name = "grupo", nullable = false, length = 20)
    private GrupoConta grupo;

    @Enumerated(EnumType.STRING)
    @Column(name = "natureza", nullable = false, length = 10)
    private NaturezaConta natureza;

    @Enumerated(EnumType.STRING)
    @Column(name = "tipo", nullable = false, length = 12)
    private TipoConta tipo;

    @Column(name = "ativa", nullable = false)
    private Boolean ativa;

    @Column(name = "conta_redutora", nullable = false)
    private Boolean contaRedutora;

    @Column(name = "conta_reduzida_id")
    private UUID contaReduzidaId;

    @Enumerated(EnumType.STRING)
    @Column(name = "classificacao_bp", length = 30)
    private ClassificacaoBP classificacaoBP;

    @Enumerated(EnumType.STRING)
    @Column(name = "classificacao_dre", length = 30)
    private ClassificacaoDRE classificacaoDRE;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumns({
            @JoinColumn(
                    name = "plano_contas_id",
                    referencedColumnName = "id",
                    insertable = false,
                    updatable = false
            ),
            @JoinColumn(
                    name = "modelo_versao",
                    referencedColumnName = "modelo_versao",
                    insertable = false,
                    updatable = false
            )
    })
    private PlanoContas planoContas;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumns({
            @JoinColumn(
                    name = "modelo_versao",
                    referencedColumnName = "modelo_versao",
                    insertable = false,
                    updatable = false
            ),
            @JoinColumn(
                    name = "modelo_conta_id",
                    referencedColumnName = "id",
                    insertable = false,
                    updatable = false
            )
    })
    private ModeloContaContabil modeloContaContabil;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumns({
            @JoinColumn(
                    name = "plano_contas_id",
                    referencedColumnName = "plano_contas_id",
                    insertable = false,
                    updatable = false
            ),
            @JoinColumn(
                    name = "conta_pai_id",
                    referencedColumnName = "id",
                    insertable = false,
                    updatable = false
            )
    })
    private ContaContabil contaPai;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumns({
            @JoinColumn(
                    name = "plano_contas_id",
                    referencedColumnName = "plano_contas_id",
                    insertable = false,
                    updatable = false
            ),
            @JoinColumn(
                    name = "conta_reduzida_id",
                    referencedColumnName = "id",
                    insertable = false,
                    updatable = false
            )
    })
    private ContaContabil contaReduzida;

    public ContaContabil(
            PlanoContas planoContas,
            ModeloContaContabil modeloContaContabil,
            UUID contaPaiId,
            UUID contaReduzidaId
    ) {
        if (planoContas == null || planoContas.getId() == null) {
            throw new IllegalArgumentException(
                    "O plano de contas deve estar persistido."
            );
        }

        if (modeloContaContabil == null
                || modeloContaContabil.getId() == null) {
            throw new IllegalArgumentException(
                    "A conta do modelo padrão deve estar persistida."
            );
        }

        if (!planoContas.getModeloVersao()
                .equals(modeloContaContabil.getModeloVersao())) {
            throw new IllegalArgumentException(
                    "A conta e o plano devem pertencer à mesma versão do modelo."
            );
        }

        this.planoContasId = planoContas.getId();
        this.modeloVersao = planoContas.getModeloVersao();
        this.modeloContaId = modeloContaContabil.getId();

        this.contaPaiId = contaPaiId;

        this.codigo = modeloContaContabil.getCodigo();
        this.nome = modeloContaContabil.getNome();
        this.grupo = modeloContaContabil.getGrupo();
        this.natureza = modeloContaContabil.getNatureza();
        this.tipo = modeloContaContabil.getTipo();

        this.ativa = true;
        this.contaRedutora = modeloContaContabil.getContaRedutora();
        this.contaReduzidaId = contaReduzidaId;

        this.classificacaoBP = modeloContaContabil.getClassificacaoBP();
        this.classificacaoDRE = modeloContaContabil.getClassificacaoDRE();
    }
}