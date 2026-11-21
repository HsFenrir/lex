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
@Table(name = "modelo_conta_contabil")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ModeloContaContabil {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "modelo_versao", nullable = false)
    private Integer modeloVersao;

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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "modelo_versao",
            referencedColumnName = "versao",
            insertable = false,
            updatable = false
    )
    private ModeloPlanoContas modeloPlanoContas;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumns({
            @JoinColumn(
                    name = "modelo_versao",
                    referencedColumnName = "modelo_versao",
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
    private ModeloContaContabil contaPai;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumns({
            @JoinColumn(
                    name = "modelo_versao",
                    referencedColumnName = "modelo_versao",
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
    private ModeloContaContabil contaReduzida;
}