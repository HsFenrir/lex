package br.com.lex.domain.contabil.repository;

import br.com.lex.domain.contabil.entity.PeriodoContabil;
import br.com.lex.domain.contabil.enums.StatusPeriodo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PeriodoContabilRepository
        extends JpaRepository<PeriodoContabil, UUID> {

    List<PeriodoContabil> findAllByEmpresaIdAndExercicioIdOrderByMes(UUID empresaId, UUID exercicioId);

    boolean existsByEmpresaIdAndStatusAndDataInicioBetween(
            UUID empresaId, StatusPeriodo status, LocalDate inicio, LocalDate fim);

    @Query(value = """
            select exists (
                select 1 from exercicio_contabil e
                cross join lateral (
                    select max(p.data_inicio) as ultimo from periodo_contabil p
                    where p.exercicio_id = e.id and p.status = 'ENCERRADO'
                ) fechado
                cross join lateral generate_series(greatest(e.data_inicio, cast(:inicio as date)),
                    case when fechado.ultimo is not null then least(fechado.ultimo, cast(:fim as date)) end,
                    interval '1 month') m(competencia)
                where e.empresa_id = :empresaId
                  and e.data_inicio <= :fim and e.data_fim >= :inicio
                  and not exists (select 1 from periodo_contabil p where p.exercicio_id = e.id
                                  and p.data_inicio = cast(m.competencia as date))
            )
            """, nativeQuery = true)
    boolean existeLacunaAteUltimoEncerramento(@Param("empresaId") UUID empresaId,
                                             @Param("inicio") LocalDate inicio, @Param("fim") LocalDate fim);

    Optional<PeriodoContabil> findByIdAndEmpresaIdAndExercicioId(
            UUID id,
            UUID empresaId,
            UUID exercicioId
    );
}
