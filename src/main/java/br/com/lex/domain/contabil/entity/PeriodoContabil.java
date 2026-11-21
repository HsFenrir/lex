package br.com.lex.domain.contabil.entity;

import br.com.lex.domain.contabil.enums.StatusPeriodo;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Entity
@Table(name = "periodo_contabil")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PeriodoContabil {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "exercicio_id", nullable = false)
    private UUID exercicioId;

    @Column(name = "empresa_id", nullable = false)
    private UUID empresaId;

    @Column(name = "ano", nullable = false)
    private Short ano;

    @Column(name = "mes", nullable = false)
    private Short mes;

    @Column(name = "data_inicio", nullable = false)
    private LocalDate dataInicio;

    @Column(name = "data_fim", nullable = false)
    private LocalDate dataFim;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 12)
    private StatusPeriodo status = StatusPeriodo.ABERTO;

    @Column(
            name = "criado_em",
            nullable = false,
            insertable = false,
            updatable = false
    )
    private OffsetDateTime criadoEm;

    @ManyToOne(fetch = FetchType.LAZY)
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
            ),
            @JoinColumn(
                    name = "ano",
                    referencedColumnName = "ano",
                    insertable = false,
                    updatable = false
            )
    })
    private ExercicioContabil exercicio;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "empresa_id",
            referencedColumnName = "id",
            insertable = false,
            updatable = false
    )
    private Empresa empresa;


    public PeriodoContabil(
            ExercicioContabil exercicio,
            Short mes,
            LocalDate dataInicio,
            LocalDate dataFim
    ) {
        this.exercicio = exercicio;
        this.mes = mes;
        this.dataInicio = dataInicio;
        this.dataFim = dataFim;
    }

    @PrePersist
    private void sincronizarChavesCompostas() {
        this.exercicioId = exercicio.getId();
        this.empresaId = exercicio.getEmpresa().getId();
        this.ano = exercicio.getAno();
    }

}
