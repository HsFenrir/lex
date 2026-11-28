package br.com.lex.application.contabil.service;

import br.com.lex.application.contabil.dto.CalendarioContabil.Competencia;
import br.com.lex.application.contabil.dto.CalendarioContabil.Exercicio;
import br.com.lex.domain.contabil.entity.Empresa;
import br.com.lex.domain.contabil.entity.ExercicioContabil;
import br.com.lex.domain.contabil.entity.PeriodoContabil;
import br.com.lex.domain.contabil.enums.StatusExercicio;
import br.com.lex.domain.contabil.repository.ExercicioContabilRepository;
import br.com.lex.domain.contabil.repository.PeriodoContabilRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CalendarioContabilService {

    private final ConsultaContabil acesso;
    private final ExercicioContabilRepository exercicios;
    private final PeriodoContabilRepository periodos;
    private final AuditoriaCadastroContabil auditoria;
    private final EntityManager em;
    private final JdbcTemplate jdbc;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Exercicio criarInicial(UUID empresaId, UUID usuarioId, YearMonth inicioEscrituracao) {
        validarInicio(inicioEscrituracao);
        if (bloquearCalendario(empresaId, usuarioId) != null) {
            throw new IllegalStateException("A empresa já possui exercício contábil.");
        }
        Empresa empresa = acesso.bloquearEmpresa(empresaId, usuarioId, LockModeType.PESSIMISTIC_READ);
        acesso.validarEmpresaAtiva(empresa);
        return criar(empresa, usuarioId, inicioEscrituracao);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Exercicio criarSucessor(UUID empresaId, UUID usuarioId, int ano) {
        Integer ultimoAno = bloquearCalendario(empresaId, usuarioId);
        if (ultimoAno == null) {
            throw new IllegalStateException("Cadastre o exercício inicial.");
        }
        if (ultimoAno == 9999 || ano != ultimoAno + 1) {
            throw new IllegalArgumentException("Informe o ano imediatamente seguinte, até 9999.");
        }
        ExercicioContabil anterior = exercicios.findByEmpresaIdAndAno(empresaId, ultimoAno.shortValue())
                .orElseThrow();
        if (anterior.getStatus() != StatusExercicio.ABERTO) {
            throw new IllegalStateException("O último exercício está encerrado sem sucessor constituído.");
        }
        if (anterior.getExercicioOrigemId() == null
                && exercicios.existsByEmpresaIdAndAnoLessThan(empresaId, anterior.getAno())) {
            throw new IllegalStateException("Conclua a continuidade patrimonial do último exercício.");
        }
        Empresa empresa = acesso.bloquearEmpresa(empresaId, usuarioId, LockModeType.PESSIMISTIC_READ);
        acesso.validarEmpresaAtiva(empresa);
        return criar(empresa, usuarioId, YearMonth.of(ano, 1));
    }

    /**
     * Completa o calendário sem alterar competências existentes. Em um exercício inicial
     * legado sem competências, o chamador informa o mês em que a escrituração começa.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<Competencia> provisionarCompetencias(UUID empresaId, UUID usuarioId, UUID exercicioId,
                                                    YearMonth inicioEscrituracao) {
        validarInicio(inicioEscrituracao);
        bloquearCalendario(empresaId, usuarioId);
        ExercicioContabil exercicio = acesso.exercicioDaEmpresa(empresaId, exercicioId);
        List<PeriodoContabil> existentes = bloquearPeriodos(empresaId, exercicioId);
        em.refresh(exercicio, LockModeType.PESSIMISTIC_WRITE);
        Empresa empresa = acesso.bloquearEmpresa(empresaId, usuarioId, LockModeType.PESSIMISTIC_READ);
        acesso.validarEmpresaAtiva(empresa);
        if (exercicio.getStatus() != StatusExercicio.ABERTO) {
            throw new IllegalStateException("O exercício deve estar aberto.");
        }

        Integer primeiroMes = jdbc.queryForObject("select primeiro_mes_escrituracao(?)", Integer.class, exercicioId);
        if (exercicios.existsByEmpresaIdAndAnoLessThan(empresaId, exercicio.getAno())) {
            primeiroMes = 1;
        }
        if (inicioEscrituracao.getYear() != exercicio.getAno()
                || (primeiroMes != null && primeiroMes != inicioEscrituracao.getMonthValue())) {
            throw new IllegalArgumentException("Informe o início de escrituração aplicável ao exercício.");
        }

        List<PeriodoContabil> completo = completar(exercicio, inicioEscrituracao.getMonthValue(), existentes);
        if (completo.size() > existentes.size()) {
            auditarCompetencias(empresaId, usuarioId, exercicioId, existentes, completo);
        }
        return completo.stream().map(CalendarioContabilService::resumo).toList();
    }

    public List<Exercicio> listarExercicios(UUID empresaId, UUID usuarioId) {
        autorizarConsulta(empresaId, usuarioId);
        return exercicios.findAllByEmpresaIdAndAnoBetweenOrderByAno(empresaId, (short) 1900, (short) 9999)
                .stream().map(CalendarioContabilService::resumo).toList();
    }

    public Exercicio consultarExercicio(UUID empresaId, UUID usuarioId, UUID exercicioId) {
        autorizarConsulta(empresaId, usuarioId);
        return resumo(acesso.exercicioDaEmpresa(empresaId, exercicioId));
    }

    public List<Competencia> listarCompetencias(UUID empresaId, UUID usuarioId, UUID exercicioId) {
        autorizarConsulta(empresaId, usuarioId);
        acesso.exercicioDaEmpresa(empresaId, exercicioId);
        return periodos.findAllByEmpresaIdAndExercicioIdOrderByMes(empresaId, exercicioId)
                .stream().map(CalendarioContabilService::resumo).toList();
    }

    public Competencia consultarCompetencia(UUID empresaId, UUID usuarioId, UUID exercicioId, UUID periodoId) {
        autorizarConsulta(empresaId, usuarioId);
        if (exercicioId == null || periodoId == null) {
            throw new IllegalArgumentException("Exercício e competência devem ser informados.");
        }
        return periodos.findByIdAndEmpresaIdAndExercicioId(periodoId, empresaId, exercicioId)
                .map(CalendarioContabilService::resumo)
                .orElseThrow(() -> new IllegalArgumentException("Competência não encontrada no exercício da empresa."));
    }

    void validarInicio(YearMonth inicio) {
        if (inicio == null || inicio.getYear() < 1900 || inicio.getYear() > 9999) {
            throw new IllegalArgumentException("Informe uma competência em ano entre 1900 e 9999.");
        }
    }

    Integer bloquearOrdemAnual(UUID empresaId) {
        return jdbc.queryForObject("select bloquear_e_validar_exercicios(?, true)", Integer.class, empresaId);
    }

    List<PeriodoContabil> bloquearPeriodos(UUID empresaId, UUID exercicioId) {
        List<PeriodoContabil> existentes = periodos.findAllByEmpresaIdAndExercicioIdOrderByMes(empresaId, exercicioId);
        for (PeriodoContabil periodo : existentes) {
            em.refresh(periodo, LockModeType.PESSIMISTIC_WRITE);
        }
        return existentes;
    }

    List<PeriodoContabil> completar(ExercicioContabil exercicio, int primeiroMes, List<PeriodoContabil> existentes) {
        Set<Short> mesesExistentes = existentes.stream().map(PeriodoContabil::getMes)
                .collect(Collectors.toSet());
        List<PeriodoContabil> completo = new ArrayList<>(existentes);
        for (short mes = (short) primeiroMes; mes <= 12; mes++) {
            if (!mesesExistentes.contains(mes)) {
                YearMonth data = YearMonth.of(exercicio.getAno(), mes);
                completo.add(periodos.save(new PeriodoContabil(exercicio, mes, data.atDay(1), data.atEndOfMonth())));
            }
        }
        completo.sort(Comparator.comparing(PeriodoContabil::getMes));
        return completo;
    }

    private Integer bloquearCalendario(UUID empresaId, UUID usuarioId) {
        autorizarConsulta(empresaId, usuarioId);
        return bloquearOrdemAnual(empresaId);
    }

    private void autorizarConsulta(UUID empresaId, UUID usuarioId) {
        acesso.usuarioAtivo(usuarioId);
        acesso.empresaDoUsuario(empresaId, usuarioId);
    }

    private Exercicio criar(Empresa empresa, UUID usuarioId, YearMonth inicio) {
        int ano = inicio.getYear();
        ExercicioContabil exercicio = exercicios.save(new ExercicioContabil(
                empresa, (short) ano, LocalDate.of(ano, 1, 1), LocalDate.of(ano, 12, 31)));
        List<PeriodoContabil> competencias = completar(exercicio, inicio.getMonthValue(), List.of());
        em.flush();
        Exercicio dto = resumo(exercicio);
        auditoria.registrar(empresa.getId(), usuarioId, "EXERCICIO_CONTABIL", dto.id(),
                "CRIAR_EXERCICIO", null, dto);
        auditarCompetencias(empresa.getId(), usuarioId, dto.id(), List.of(), competencias);
        return dto;
    }

    private void auditarCompetencias(UUID empresaId, UUID usuarioId, UUID exercicioId,
                                     List<PeriodoContabil> antes, List<PeriodoContabil> depois) {
        em.flush();
        auditoria.registrar(empresaId, usuarioId, "EXERCICIO_CONTABIL", exercicioId,
                "PROVISIONAR_COMPETENCIAS", antes.stream().map(CalendarioContabilService::resumo).toList(),
                depois.stream().map(CalendarioContabilService::resumo).toList());
    }

    private static Exercicio resumo(ExercicioContabil exercicio) {
        return new Exercicio(exercicio.getId(), exercicio.getEmpresa().getId(), exercicio.getAno(),
                exercicio.getDataInicio(), exercicio.getDataFim(), exercicio.getStatus(),
                exercicio.getExercicioOrigemId());
    }

    private static Competencia resumo(PeriodoContabil periodo) {
        return new Competencia(periodo.getId(), periodo.getExercicioId(), periodo.getAno(), periodo.getMes(),
                periodo.getDataInicio(), periodo.getDataFim(), periodo.getStatus());
    }
}
