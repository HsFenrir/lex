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

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "plano_contas")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PlanoContas {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "empresa_id", nullable = false)
    private UUID empresaId;

    @Column(name = "modelo_versao", nullable = false)
    private Integer modeloVersao;

    @Column(
            name = "criado_em",
            nullable = false,
            insertable = false,
            updatable = false
    )
    private OffsetDateTime criadoEm;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "empresa_id",
            referencedColumnName = "id",
            insertable = false,
            updatable = false
    )
    private Empresa empresa;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "modelo_versao",
            referencedColumnName = "versao",
            insertable = false,
            updatable = false
    )
    private ModeloPlanoContas modeloPlanoContas;

    public PlanoContas(Empresa empresa, ModeloPlanoContas modeloPlanoContas) {
        if (empresa == null || empresa.getId() == null) {
            throw new IllegalArgumentException(
                    "A empresa deve estar persistida."
            );
        }

        if (modeloPlanoContas == null || modeloPlanoContas.getVersao() == null) {
            throw new IllegalArgumentException(
                    "O modelo do plano de contas deve estar persistido."
            );
        }

        this.empresaId = empresa.getId();
        this.modeloVersao = modeloPlanoContas.getVersao();
    }
}