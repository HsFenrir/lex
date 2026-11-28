package br.com.lex.application.contabil.service;

import br.com.lex.application.contabil.dto.AberturaContabil.SaldoPorConta;
import br.com.lex.application.contabil.dto.ApropriacaoDepreciacao;
import br.com.lex.application.contabil.dto.ProcessamentoDepreciacao;
import br.com.lex.application.contabil.dto.ProcessamentoDepreciacao.AtivoNaoElegivel;
import br.com.lex.domain.contabil.entity.AtivoPatrimonial;
import br.com.lex.domain.contabil.entity.Empresa;
import br.com.lex.domain.contabil.entity.ExercicioContabil;
import br.com.lex.domain.contabil.entity.LancamentoContabil;
import br.com.lex.domain.contabil.entity.PeriodoContabil;
import br.com.lex.domain.contabil.entity.Usuario;
import br.com.lex.domain.contabil.enums.OrigemLancamento;
import br.com.lex.domain.contabil.enums.TipoPartida;
import br.com.lex.domain.contabil.repository.ApropriacaoDepreciacaoRepository;
import br.com.lex.domain.contabil.repository.AtivoPatrimonialRepository;
import br.com.lex.domain.contabil.repository.PeriodoContabilRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DepreciacaoService {

    private final AtivoPatrimonialService ativos;
    private final AtivoPatrimonialRepository cadastro;
    private final LancamentoContabilService contabilidade;
    private final PeriodoContabilRepository periodos;
    private final ApropriacaoDepreciacaoRepository apropriacoes;
    private final EntityManager em;
    private final JdbcTemplate jdbc;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ApropriacaoDepreciacao apropriar(UUID empresaId, UUID usuarioId, UUID exercicioId, UUID periodoId, UUID ativoId) {
        PeriodoContabil periodo = bloquearCalendario(empresaId, usuarioId, exercicioId, periodoId);
        var ativo = ativos.bloquearAtivo(empresaId, ativoId);
        var plano = planejar(ativo, YearMonth.from(periodo.getDataFim()));
        if (plano.situacao() != Situacao.APTA) {
            throw new IllegalStateException(plano.diagnostico());
        }
        if (plano.valor().signum() > 0) {
            ativos.bloquearEValidarContasDepreciacao(List.of(ativo));
        }
        return escriturar(periodo, usuarioId, plano);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ProcessamentoDepreciacao processarCompetencia(UUID empresaId, UUID usuarioId, UUID exercicioId, UUID periodoId) {
        PeriodoContabil periodo = bloquearCalendario(empresaId, usuarioId, exercicioId, periodoId);
        var competencia = YearMonth.from(periodo.getDataFim());

        // Bloqueie todos os ativos por ID antes das contas e da sequência, inclusive quando houver apropriação individual.
        List<PlanoApropriacao> aptas = new ArrayList<>();
        List<ApropriacaoDepreciacao> jaProcessadas = new ArrayList<>();
        List<AtivoNaoElegivel> inelegiveis = new ArrayList<>();
        List<String> pendencias = new ArrayList<>();
        for (UUID ativoId : cadastro.listarIdsParaProcessamento(empresaId)) {
            var ativo = ativos.bloquearAtivo(empresaId, ativoId);
            var plano = planejar(ativo, competencia);
            switch (plano.situacao()) {
                case APTA -> aptas.add(plano);
                case PROCESSADA -> jaProcessadas.add(plano.existente());
                case INELEGIVEL -> inelegiveis.add(new AtivoNaoElegivel(ativoId, ativo.getCodigo(), plano.diagnostico()));
                case ANTERIOR_PENDENTE -> pendencias.add(ativo.getCodigo() + " (" + plano.proxima() + ")");
            }
        }
        if (!pendencias.isEmpty()) {
            throw new IllegalStateException("Depreciação anterior pendente por ativo e competência: "
                    + String.join(", ", pendencias));
        }
        ativos.bloquearEValidarContasDepreciacao(aptas.stream().filter(plano -> plano.valor().signum() > 0)
                .map(PlanoApropriacao::ativo).toList());

        List<ApropriacaoDepreciacao> processadas = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        for (PlanoApropriacao plano : aptas) {
            processadas.add(escriturar(periodo, usuarioId, plano));
            total = total.add(plano.valor());
        }
        return new ProcessamentoDepreciacao(competencia, processadas, inelegiveis, jaProcessadas, total);
    }

    private PeriodoContabil bloquearCalendario(UUID empresaId, UUID usuarioId, UUID exercicioId, UUID periodoId) {
        ativos.validarIsolamento();
        contabilidade.validarAcessoOperacional(empresaId, usuarioId);
        var periodo = periodos.findByIdAndEmpresaIdAndExercicioId(periodoId, empresaId, exercicioId)
                .orElseThrow(() -> new IllegalArgumentException("Período não pertence ao exercício da empresa."));
        contabilidade.validarDestinoOperacional(empresaId, usuarioId, exercicioId, periodoId, periodo.getDataFim());
        return periodo;
    }

    private PlanoApropriacao planejar(AtivoPatrimonial ativo, YearMonth competencia) {
        if (!ativo.isDepreciavel() || ativo.getDataDisponibilidade() == null) {
            return new PlanoApropriacao(ativo, Situacao.INELEGIVEL, null, null, null,
                    "O ativo deve ser depreciável e ter disponibilidade para uso registrada.");
        }
        List<ApropriacaoDepreciacao> historico = apropriacoes.listar(ativo.getEmpresaId(), ativo.getId());
        for (var existente : historico) {
            if (existente.competencia().equals(competencia)) {
                return new PlanoApropriacao(ativo, Situacao.PROCESSADA, null, existente, null,
                        "A competência " + competencia + " já foi processada para o ativo.");
            }
        }
        BigDecimal acumulado = historico.stream().map(ApropriacaoDepreciacao::valor)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal base = ativo.baseInicialLex();
        int vidaUtil = ativo.vidaUtilInicialLex();
        if (acumulado.compareTo(base) >= 0 || historico.size() >= vidaUtil) {
            return new PlanoApropriacao(ativo, Situacao.INELEGIVEL, null, null, null,
                    "O ativo não possui base depreciável ou vida útil remanescente.");
        }
        var proxima = ativo.primeiraCompetenciaLex().plusMonths(historico.size());
        if (!competencia.equals(proxima)) {
            var situacao = proxima.isBefore(competencia) ? Situacao.ANTERIOR_PENDENTE : Situacao.INELEGIVEL;
            return new PlanoApropriacao(ativo, situacao, null, null, proxima,
                    "A próxima competência do ativo é " + proxima + ".");
        }

        BigDecimal devidoAteMes = base.multiply(BigDecimal.valueOf(historico.size() + 1L))
                .divide(BigDecimal.valueOf(vidaUtil), 2, RoundingMode.HALF_UP);
        BigDecimal valor = devidoAteMes.subtract(acumulado);
        if (valor.signum() < 0) {
            throw new IllegalStateException("O histórico excede a depreciação acumulada prevista para a competência.");
        }
        return new PlanoApropriacao(ativo, Situacao.APTA, valor, null, null, null);
    }

    private ApropriacaoDepreciacao escriturar(PeriodoContabil periodo, UUID usuarioId, PlanoApropriacao plano) {
        var ativo = plano.ativo();
        UUID empresaId = ativo.getEmpresaId();
        UUID exercicioId = periodo.getExercicioId();
        var dataContabil = periodo.getDataFim();
        var competencia = YearMonth.from(dataContabil);
        BigDecimal valor = plano.valor();
        LancamentoContabil lancamento = null;
        if (valor.signum() > 0) {
            lancamento = contabilidade.escriturarTecnico(
                    em.getReference(Empresa.class, empresaId), em.getReference(Usuario.class, usuarioId),
                    em.getReference(ExercicioContabil.class, exercicioId), periodo, dataContabil,
                    "Depreciação de " + ativo.getCodigo() + " — " + competencia, OrigemLancamento.DEPRECIACAO,
                    List.of(new SaldoPorConta(ativo.getContaDespesaId(), TipoPartida.DEBITO, valor),
                            new SaldoPorConta(ativo.getContaRedutoraId(), TipoPartida.CREDITO, valor)));
        }
        var apropriacao = new ApropriacaoDepreciacao(UUID.randomUUID(), ativo.getId(), exercicioId, periodo.getId(), competencia,
                valor, lancamento == null ? null : lancamento.getId(), lancamento == null ? null : lancamento.getNumero());
        apropriacoes.inserir(empresaId, usuarioId, apropriacao);
        auditar(empresaId, usuarioId, apropriacao);
        return apropriacao;
    }

    private enum Situacao {
        APTA, INELEGIVEL, PROCESSADA, ANTERIOR_PENDENTE
    }

    private record PlanoApropriacao(
            AtivoPatrimonial ativo, Situacao situacao, BigDecimal valor, ApropriacaoDepreciacao existente,
            YearMonth proxima, String diagnostico
    ) {
    }

    private void auditar(UUID empresaId, UUID usuarioId, ApropriacaoDepreciacao apropriacao) {
        jdbc.update("""
                insert into auditoria_evento (empresa_id, usuario_id, entidade, entidade_id, acao, dados_novos)
                values (?, ?, 'apropriacao_depreciacao', ?, 'APROPRIAR_DEPRECIACAO', jsonb_build_object(
                    'ativoId', cast(? as uuid), 'competencia', cast(? as text), 'valor', cast(? as numeric),
                    'lancamentoId', cast(? as uuid), 'numeroContabil', cast(? as bigint)))
                """, empresaId, usuarioId, apropriacao.id(), apropriacao.ativoId(), apropriacao.competencia().toString(),
                apropriacao.valor(), apropriacao.lancamentoId(), apropriacao.numeroContabil());
    }
}
