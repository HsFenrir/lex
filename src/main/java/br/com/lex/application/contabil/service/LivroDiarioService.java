package br.com.lex.application.contabil.service;

import br.com.lex.application.contabil.dto.LivroDiario;
import br.com.lex.domain.contabil.entity.Empresa;
import br.com.lex.domain.contabil.entity.ExercicioContabil;
import br.com.lex.domain.contabil.enums.TipoPartida;
import br.com.lex.domain.contabil.repository.PartidaContabilRepository;
import br.com.lex.domain.contabil.repository.PartidaContabilRepository.MovimentoEscriturado;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toList;

@Service
@RequiredArgsConstructor
public class LivroDiarioService {

    private static final BigDecimal ZERO_MONETARIO = new BigDecimal("0.00");

    private final ConsultaContabil consulta;
    private final PartidaContabilRepository partidas;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public LivroDiario emitir(UUID empresaId, UUID usuarioId, UUID exercicioId, LocalDate inicio, LocalDate fim) {
        ConsultaContabil.Contexto contexto = consulta.validar(empresaId, usuarioId, exercicioId, inicio, fim);
        Empresa empresa = contexto.empresa();
        ExercicioContabil exercicio = contexto.exercicio();

        List<LivroDiario.Lancamento> lancamentos = partidas
                .buscarMovimentosEscriturados(empresaId, exercicioId, inicio, fim).stream()
                .collect(groupingBy(MovimentoEscriturado::getLancamentoId, LinkedHashMap::new, toList()))
                .values().stream()
                .map(movimentos -> montarLancamento(exercicioId, movimentos))
                .toList();

        BigDecimal totalDebitos = ZERO_MONETARIO;
        BigDecimal totalCreditos = ZERO_MONETARIO;
        for (LivroDiario.Lancamento lancamento : lancamentos) {
            totalDebitos = totalDebitos.add(lancamento.totalDebitos());
            totalCreditos = totalCreditos.add(lancamento.totalCreditos());
        }

        return new LivroDiario(
                new LivroDiario.Empresa(empresaId, empresa.getRazaoSocial(), empresa.getCnpj()),
                new LivroDiario.Exercicio(exercicioId, exercicio.getAno(), exercicio.getDataInicio(), exercicio.getDataFim()),
                inicio, fim, lancamentos, totalDebitos, totalCreditos);
    }

    private LivroDiario.Lancamento montarLancamento(UUID exercicioId, List<MovimentoEscriturado> movimentos) {
        MovimentoEscriturado cabecalho = movimentos.getFirst();
        LivroDiario.ReferenciaLancamento referencia = new LivroDiario.ReferenciaLancamento(
                cabecalho.getLancamentoId(), exercicioId, cabecalho.getNumero());
        LivroDiario.ReferenciaLancamento original = null;
        if (cabecalho.getOriginalId() != null) {
            original = new LivroDiario.ReferenciaLancamento(
                    cabecalho.getOriginalId(), cabecalho.getExercicioOriginalId(), cabecalho.getNumeroOriginal());
        }

        List<LivroDiario.Partida> itens = new ArrayList<>(movimentos.size());
        BigDecimal totalDebitos = ZERO_MONETARIO;
        BigDecimal totalCreditos = ZERO_MONETARIO;
        for (MovimentoEscriturado movimento : movimentos) {
            LivroDiario.Partida partida = new LivroDiario.Partida(
                    movimento.getPartidaId(), movimento.getContaId(), movimento.getCodigoConta(), movimento.getNomeConta(),
                    movimento.getTipo(), movimento.getValor(), movimento.getHistoricoPartida());
            itens.add(partida);
            if (partida.tipo() == TipoPartida.DEBITO) {
                totalDebitos = totalDebitos.add(partida.valor());
            } else {
                totalCreditos = totalCreditos.add(partida.valor());
            }
        }

        return new LivroDiario.Lancamento(referencia, cabecalho.getPeriodoId(), cabecalho.getData(),
                cabecalho.getHistoricoLancamento(), cabecalho.getOrigem(), cabecalho.getStatus(), original,
                itens, totalDebitos, totalCreditos);
    }
}
