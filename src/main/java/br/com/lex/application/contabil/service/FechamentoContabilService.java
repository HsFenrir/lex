package br.com.lex.application.contabil.service;

import br.com.lex.application.contabil.dto.AberturaContabil.SaldoPorConta;
import br.com.lex.application.contabil.dto.Balancete;
import br.com.lex.application.contabil.dto.Balancete.Conta;
import br.com.lex.domain.contabil.entity.Empresa;
import br.com.lex.domain.contabil.entity.ExercicioContabil;
import br.com.lex.domain.contabil.entity.PeriodoContabil;
import br.com.lex.domain.contabil.entity.Usuario;
import br.com.lex.domain.contabil.enums.ClassificacaoBP;
import br.com.lex.domain.contabil.enums.GrupoConta;
import br.com.lex.domain.contabil.enums.OrigemLancamento;
import br.com.lex.domain.contabil.enums.StatusExercicio;
import br.com.lex.domain.contabil.enums.StatusLancamento;
import br.com.lex.domain.contabil.enums.StatusPeriodo;
import br.com.lex.domain.contabil.enums.TipoConta;
import br.com.lex.domain.contabil.enums.TipoPartida;
import br.com.lex.domain.contabil.repository.ContaContabilRepository;
import br.com.lex.domain.contabil.repository.ExercicioContabilRepository;
import br.com.lex.domain.contabil.repository.LancamentoContabilRepository;
import br.com.lex.domain.contabil.repository.PeriodoContabilRepository;
import br.com.lex.domain.contabil.repository.PlanoContasRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class FechamentoContabilService {

    private static final String LUCROS_ACUMULADOS_MODELO_V1 = "3.3.001";
    private static final String PREJUIZOS_ACUMULADOS_MODELO_V1 = "3.3.002";

    private final ConsultaContabil consulta;
    private final ExercicioContabilRepository exercicios;
    private final PeriodoContabilRepository periodos;
    private final LancamentoContabilRepository lancamentos;
    private final PlanoContasRepository planos;
    private final ContaContabilRepository contas;
    private final BalanceteService balancete;
    private final LancamentoContabilService escrituracao;
    private final EntityManager em;
    private final JdbcTemplate jdbc;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PeriodoEncerrado encerrarPeriodo(UUID empresaId, UUID usuarioId, UUID exercicioId, UUID periodoId) {
        validarIsolamento();
        PeriodoContabil periodo = periodos.findByIdAndEmpresaIdAndExercicioId(periodoId, empresaId, exercicioId)
                .orElseThrow(() -> new IllegalArgumentException("Período não pertence ao exercício da empresa."));
        ConsultaContabil.Contexto contexto = consulta.validar(
                empresaId, usuarioId, exercicioId, periodo.getDataInicio(), periodo.getDataFim());
        em.refresh(periodo, LockModeType.PESSIMISTIC_WRITE);
        em.refresh(contexto.exercicio(), LockModeType.PESSIMISTIC_WRITE);
        bloquearAutorizacao(contexto.empresa(), usuarioId);
        validarAberto(contexto.exercicio());
        if (periodo.getStatus() != StatusPeriodo.ABERTO) {
            throw new IllegalStateException("O período deve estar aberto.");
        }
        if (periodo.getMes() == 12) {
            throw new IllegalStateException("Dezembro é reservado para o encerramento anual.");
        }
        if (periodos.existsByEmpresaIdAndExercicioIdAndMesLessThanAndStatus(
                empresaId, exercicioId, periodo.getMes(), StatusPeriodo.ABERTO)) {
            throw new IllegalStateException("Encerre primeiro os períodos anteriores cadastrados.");
        }
        if (lancamentos.existsByEmpresaIdAndExercicioIdAndPeriodoIdAndStatus(
                empresaId, exercicioId, periodoId, StatusLancamento.RASCUNHO)) {
            throw new IllegalStateException("O período possui lançamentos em rascunho.");
        }
        validarEquilibrio(balancete.emitir(empresaId, usuarioId, exercicioId, periodo.getDataInicio(), periodo.getDataFim()));
        periodo.encerrar();
        em.flush();
        auditar(empresaId, usuarioId, "PERIODO_CONTABIL", periodoId, "ENCERRAR_PERIODO",
                exercicioId, periodo.getDataFim(), null, null);
        return new PeriodoEncerrado(exercicioId, periodoId, periodo.getDataFim());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ExercicioEncerrado encerrarExercicio(UUID empresaId, UUID usuarioId, UUID exercicioId) {
        validarIsolamento();
        ExercicioContabil origem = exercicios.findByIdAndEmpresaId(exercicioId, empresaId)
                .orElseThrow(() -> new IllegalArgumentException("Exercício não encontrado na empresa."));
        ConsultaContabil.Contexto contexto = consulta.validar(
                empresaId, usuarioId, exercicioId, origem.getDataInicio(), origem.getDataFim());
        if (origem.getAno() == 9999) {
            throw new IllegalStateException("O próximo exercício está fora do calendário suportado.");
        }

        List<PeriodoContabil> calendarioOrigem = bloquearPeriodos(empresaId, exercicioId);
        PeriodoContabil dezembro = calendarioOrigem.stream().filter(p -> p.getMes() == 12).findFirst()
                .orElseThrow(() -> new IllegalStateException("Cadastre dezembro antes do encerramento anual."));
        short anoDestino = (short) (origem.getAno() + 1);
        ExercicioContabil destino = exercicios.findByEmpresaIdAndAno(empresaId, anoDestino).orElse(null);
        List<PeriodoContabil> calendarioDestino = destino == null ? List.of() : bloquearPeriodos(empresaId, destino.getId());

        em.refresh(origem, LockModeType.PESSIMISTIC_WRITE);
        if (destino != null) {
            em.refresh(destino, LockModeType.PESSIMISTIC_WRITE);
        }
        validarAberto(origem);
        escrituracao.validarContinuidade(empresaId, origem);
        if (dezembro.getStatus() != StatusPeriodo.ABERTO || calendarioOrigem.stream()
                .anyMatch(p -> p.getMes() < 12 && p.getStatus() != StatusPeriodo.ENCERRADO)) {
            throw new IllegalStateException("Encerre os períodos anteriores cadastrados e mantenha dezembro aberto.");
        }
        if (lancamentos.existsByEmpresaIdAndExercicioIdAndStatus(empresaId, exercicioId, StatusLancamento.RASCUNHO)) {
            throw new IllegalStateException("O exercício possui lançamentos em rascunho.");
        }
        if (lancamentos.existsByEmpresaIdAndExercicioIdAndOrigemAndStatusIn(empresaId, exercicioId,
                OrigemLancamento.ENCERRAMENTO, Set.of(StatusLancamento.values()))) {
            throw new IllegalStateException("O exercício já possui lançamento de encerramento.");
        }
        validarDestino(empresaId, destino, calendarioDestino);
        Usuario usuario = bloquearAutorizacao(contexto.empresa(), usuarioId);
        Balancete apurado = balancete.emitir(empresaId, usuarioId, exercicioId, origem.getDataInicio(), origem.getDataFim());
        validarEquilibrio(apurado);
        PartidasTecnicas tecnicas = prepararPartidas(empresaId, apurado);
        bloquearContas(empresaId, tecnicas);

        ExercicioContabil proximo = destino;
        if (proximo == null) {
            LocalDate inicio = origem.getDataFim().plusDays(1);
            proximo = exercicios.save(new ExercicioContabil(contexto.empresa(), anoDestino, inicio, inicio.plusYears(1).minusDays(1)));
        }
        PeriodoContabil janeiro = completarCalendario(proximo, calendarioDestino);
        UUID encerramentoId = escriturar(contexto.empresa(), usuario, origem, dezembro, origem.getDataFim(),
                "Encerramento anual de " + origem.getAno(), OrigemLancamento.ENCERRAMENTO, tecnicas.encerramento());
        proximo.receberContinuidadeDe(origem);
        UUID aberturaId = escriturar(contexto.empresa(), usuario, proximo, janeiro, proximo.getDataInicio(),
                "Continuidade patrimonial de " + origem.getAno(), OrigemLancamento.ABERTURA, tecnicas.abertura());

        dezembro.encerrar();
        origem.encerrar();
        em.flush();
        auditar(empresaId, usuarioId, "EXERCICIO_CONTABIL", origem.getId(), "ENCERRAR_EXERCICIO",
                origem.getId(), origem.getDataFim(), proximo.getId(), encerramentoId);
        auditar(empresaId, usuarioId, "EXERCICIO_CONTABIL", proximo.getId(), "TRANSPORTAR_SALDOS",
                origem.getId(), proximo.getDataInicio(), proximo.getId(), aberturaId);
        return new ExercicioEncerrado(origem.getId(), proximo.getId(), encerramentoId, aberturaId, tecnicas.resultado());
    }

    private void validarIsolamento() {
        if (!"read committed".equals(jdbc.queryForObject("show transaction_isolation", String.class))) {
            throw new IllegalStateException("O fechamento exige uma transação READ COMMITTED.");
        }
    }

    private List<PeriodoContabil> bloquearPeriodos(UUID empresaId, UUID exercicioId) {
        List<PeriodoContabil> calendario = periodos.findAllByEmpresaIdAndExercicioIdOrderByMes(empresaId, exercicioId);
        for (PeriodoContabil periodo : calendario) {
            em.refresh(periodo, LockModeType.PESSIMISTIC_WRITE);
        }
        return calendario;
    }

    private Usuario bloquearAutorizacao(Empresa empresa, UUID usuarioId) {
        em.refresh(empresa, LockModeType.PESSIMISTIC_READ);
        Usuario usuario = em.find(Usuario.class, usuarioId);
        em.refresh(usuario, LockModeType.PESSIMISTIC_READ);
        if (!usuario.getId().equals(empresa.getUsuario().getId()) || !usuario.isAtivo()
                || !empresa.isAtiva() || empresa.getArquivadaEm() != null) {
            throw new IllegalStateException("A autorização para o fechamento foi alterada.");
        }
        return usuario;
    }

    private void validarAberto(ExercicioContabil exercicio) {
        if (exercicio.getStatus() != StatusExercicio.ABERTO) {
            throw new IllegalStateException("O exercício deve estar aberto.");
        }
    }

    private void validarDestino(UUID empresaId, ExercicioContabil destino, List<PeriodoContabil> calendario) {
        if (destino == null) {
            return;
        }
        if (destino.getStatus() != StatusExercicio.ABERTO || destino.getExercicioOrigemId() != null
                || calendario.stream().anyMatch(p -> p.getStatus() != StatusPeriodo.ABERTO)
                || lancamentos.existsByEmpresaIdAndExercicioIdAndStatusIn(empresaId, destino.getId(),
                        Set.of(StatusLancamento.CONFIRMADO, StatusLancamento.ESTORNADO))) {
            throw new IllegalStateException("O exercício de destino está incompatível ou já possui escrituração.");
        }
    }

    private PeriodoContabil completarCalendario(ExercicioContabil destino, List<PeriodoContabil> existentes) {
        Map<Short, PeriodoContabil> porMes = existentes.stream()
                .collect(Collectors.toMap(PeriodoContabil::getMes, periodo -> periodo));
        for (short mes = 1; mes <= 12; mes++) {
            if (!porMes.containsKey(mes)) {
                YearMonth data = YearMonth.of(destino.getAno(), mes);
                porMes.put(mes, periodos.save(new PeriodoContabil(destino, mes, data.atDay(1), data.atEndOfMonth())));
            }
        }
        return porMes.get((short) 1);
    }

    private void validarEquilibrio(Balancete apurado) {
        Balancete.Equilibrio equilibrio = apurado.equilibrio();
        if (!equilibrio.saldoAnterior() || !equilibrio.movimentos() || !equilibrio.saldoFinal()) {
            throw new IllegalStateException("A escrituração está desequilibrada no recorte do fechamento.");
        }
    }

    private PartidasTecnicas prepararPartidas(UUID empresaId, Balancete apurado) {
        Map<UUID, Conta> plano = apurado.linhas().stream().map(Balancete.Linha::conta)
                .collect(Collectors.toMap(Conta::id, conta -> conta));
        Map<UUID, BigDecimal> patrimoniais = new LinkedHashMap<>();
        List<SaldoPorConta> encerramento = new ArrayList<>();
        BigDecimal resultado = BigDecimal.ZERO;
        for (Balancete.Linha linha : apurado.linhas()) {
            Conta conta = linha.conta();
            BigDecimal saldo = linha.saldos().saldoFinal().valor();
            if (conta.tipo() != TipoConta.ANALITICA || saldo.signum() == 0) {
                continue;
            }
            if (ClassificacaoDemonstracoes.contaDeResultado(conta)) {
                ClassificacaoDemonstracoes.classificarDRE(conta, plano);
                adicionarPartidas(encerramento, conta, saldo.negate());
                resultado = resultado.subtract(saldo);
            } else {
                ClassificacaoDemonstracoes.classificarBP(conta, plano);
                patrimoniais.put(conta.id(), saldo);
            }
        }
        if (resultado.signum() != 0) {
            String codigo = resultado.signum() > 0 ? LUCROS_ACUMULADOS_MODELO_V1 : PREJUIZOS_ACUMULADOS_MODELO_V1;
            UUID contaId = contas.buscarContaDoModeloPadrao(empresaId, codigo)
                    .orElseThrow(() -> new IllegalStateException("Conta patrimonial de resultado ausente no plano: " + codigo));
            Conta conta = plano.get(contaId);
            if (conta == null || conta.tipo() != TipoConta.ANALITICA || conta.grupo() != GrupoConta.PATRIMONIO_LIQUIDO
                    || conta.contaRedutora() || ClassificacaoDemonstracoes.classificarBP(conta, plano) != ClassificacaoBP.PATRIMONIO_LIQUIDO) {
                throw new IllegalStateException("Conta patrimonial de resultado incompatível: " + codigo);
            }
            adicionarPartidas(encerramento, conta, resultado.negate());
            patrimoniais.merge(contaId, resultado.negate(), BigDecimal::add);
        }
        List<SaldoPorConta> abertura = new ArrayList<>();
        patrimoniais.forEach((contaId, saldo) -> adicionarPartidas(abertura, plano.get(contaId), saldo));
        return new PartidasTecnicas(encerramento, abertura, resultado);
    }

    private void adicionarPartidas(List<SaldoPorConta> partidas, Conta conta, BigDecimal saldo) {
        if (saldo.signum() == 0) {
            return;
        }
        if (!conta.ativa()) {
            throw new IllegalStateException("Conta inativa com saldo a escriturar no fechamento: " + conta.codigo());
        }
        TipoPartida tipo = saldo.signum() > 0 ? TipoPartida.DEBITO : TipoPartida.CREDITO;
        LancamentoContabilService.adicionarSaldoEmPartidas(partidas, conta.id(), tipo, saldo.abs());
    }

    private void bloquearContas(UUID empresaId, PartidasTecnicas tecnicas) {
        Set<UUID> ids = java.util.stream.Stream.concat(tecnicas.encerramento().stream(), tecnicas.abertura().stream())
                .map(SaldoPorConta::contaId).collect(Collectors.toSet());
        if (!ids.isEmpty()) {
            UUID plano = planos.findByEmpresaId(empresaId).orElseThrow().getId();
            if (contas.bloquearContasAptas(plano, ids).size() != ids.size()) {
                throw new IllegalStateException("As contas do fechamento devem permanecer analíticas e ativas.");
            }
        }
    }

    private UUID escriturar(Empresa empresa, Usuario usuario, ExercicioContabil exercicio, PeriodoContabil periodo,
                            LocalDate data, String historico, OrigemLancamento origem, List<SaldoPorConta> partidas) {
        if (partidas.isEmpty()) {
            return null;
        }
        return escrituracao.escriturarTecnico(empresa, usuario, exercicio, periodo, data, historico, origem, partidas).getId();
    }

    private void auditar(UUID empresa, UUID usuario, String entidade, UUID entidadeId, String acao,
                         UUID origem, LocalDate data, UUID destino, UUID lancamento) {
        jdbc.update("""
                insert into auditoria_evento (empresa_id, usuario_id, entidade, entidade_id, acao, dados_novos)
                values (?, ?, ?, ?, ?, jsonb_build_object('exercicioOrigemId', cast(? as uuid),
                        'dataContabil', cast(? as date), 'exercicioDestinoId', cast(? as uuid),
                        'lancamentoId', cast(? as uuid)))
                """, empresa, usuario, entidade, entidadeId, acao, origem, data, destino, lancamento);
    }

    public record PeriodoEncerrado(UUID exercicioId, UUID periodoId, LocalDate dataFinal) {
    }

    public record ExercicioEncerrado(UUID exercicioOrigemId, UUID exercicioDestinoId,
                                      UUID lancamentoEncerramentoId, UUID lancamentoAberturaId, BigDecimal resultado) {
    }

    private record PartidasTecnicas(List<SaldoPorConta> encerramento, List<SaldoPorConta> abertura, BigDecimal resultado) {
    }
}
