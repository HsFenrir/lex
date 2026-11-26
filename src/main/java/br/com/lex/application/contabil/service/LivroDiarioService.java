package br.com.lex.application.contabil.service;

import br.com.lex.application.contabil.dto.LivroDiario;
import br.com.lex.domain.contabil.entity.Empresa;
import br.com.lex.domain.contabil.entity.ExercicioContabil;
import br.com.lex.domain.contabil.entity.Usuario;
import br.com.lex.domain.contabil.enums.TipoPartida;
import br.com.lex.domain.contabil.repository.EmpresaRepository;
import br.com.lex.domain.contabil.repository.ExercicioContabilRepository;
import br.com.lex.domain.contabil.repository.PartidaContabilRepository;
import br.com.lex.domain.contabil.repository.PartidaContabilRepository.MovimentoEscriturado;
import br.com.lex.domain.contabil.repository.UsuarioRepository;
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

    private final UsuarioRepository usuarios;
    private final EmpresaRepository empresas;
    private final ExercicioContabilRepository exercicios;
    private final PartidaContabilRepository partidas;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public LivroDiario emitir(UUID empresaId, UUID usuarioId, UUID exercicioId, LocalDate inicio, LocalDate fim) {
        if (empresaId == null || usuarioId == null || exercicioId == null || inicio == null || fim == null) {
            throw new IllegalArgumentException("Empresa, usuário, exercício e datas devem ser informados.");
        }

        Usuario usuario = usuarios.findById(usuarioId)
                .orElseThrow(() -> new IllegalArgumentException("Usuário não encontrado."));
        if (!usuario.isAtivo()) {
            throw new IllegalStateException("O usuário deve estar ativo.");
        }

        Empresa empresa = empresas.findByIdAndUsuarioId(empresaId, usuarioId)
                .orElseThrow(() -> new IllegalArgumentException("Empresa não encontrada ou não pertence ao usuário."));
        if (!empresa.isAtiva() || empresa.getArquivadaEm() != null) {
            throw new IllegalStateException("A empresa deve estar ativa e não arquivada.");
        }

        ExercicioContabil exercicio = exercicios.findByIdAndEmpresaId(exercicioId, empresaId)
                .orElseThrow(() -> new IllegalArgumentException("Exercício não encontrado para a empresa."));
        if (inicio.isAfter(fim) || inicio.isBefore(exercicio.getDataInicio()) || fim.isAfter(exercicio.getDataFim())) {
            throw new IllegalArgumentException("O intervalo deve estar contido nas datas do exercício.");
        }

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
