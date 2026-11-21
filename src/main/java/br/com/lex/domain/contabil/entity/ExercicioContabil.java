package br.com.lex.domain.contabil.entity;

import br.com.lex.domain.contabil.enums.StatusExercicio;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Entity
@Table(name = "exercicio_contabil")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExercicioContabil {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "empresa_id", nullable = false)
    private Empresa empresa;

    @Column(name = "ano", nullable = false)
    private Short ano;

    @Column(name = "data_inicio", nullable = false)
    private LocalDate dataInicio;

    @Column(name = "data_fim", nullable = false)
    private LocalDate dataFim;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 12)
    private StatusExercicio status = StatusExercicio.ABERTO;

    @Column(
            name = "criado_em",
            nullable = false,
            insertable = false,
            updatable = false
    )
    private OffsetDateTime criadoEm;

    public ExercicioContabil(
            Empresa empresa,
            Short ano,
            LocalDate dataInicio,
            LocalDate dataFim
    ) {
        this.empresa = empresa;
        this.ano = ano;
        this.dataInicio = dataInicio;
        this.dataFim = dataFim;
    }
}
