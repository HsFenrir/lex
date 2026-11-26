package br.com.lex.application.contabil.service;

import br.com.lex.application.contabil.dto.LivroRazao;
import br.com.lex.domain.contabil.ApuracaoSaldos;
import br.com.lex.domain.contabil.enums.TipoConta;
import br.com.lex.domain.contabil.repository.ContaContabilRepository;
import br.com.lex.domain.contabil.repository.ContaContabilRepository.DadosConta;
import br.com.lex.domain.contabil.repository.PartidaContabilRepository;
import br.com.lex.domain.contabil.repository.PartidaContabilRepository.MovimentoEscriturado;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class LivroRazaoService {

    private final ConsultaContabil consulta;
    private final ContaContabilRepository contas;
    private final PartidaContabilRepository partidas;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public LivroRazao emitir(UUID empresaId, UUID usuarioId, UUID exercicioId, UUID contaId,
                             LocalDate inicio, LocalDate fim) {
        ConsultaContabil.Contexto contexto = consulta.validar(empresaId, usuarioId, exercicioId, inicio, fim);
        if (contaId == null) {
            throw new IllegalArgumentException("Uma conta analítica deve ser informada.");
        }
        DadosConta conta = contas.buscarDadosDaConta(empresaId, contaId)
                .orElseThrow(() -> new IllegalArgumentException("Conta não encontrada no plano da empresa."));
        if (conta.getTipo() != TipoConta.ANALITICA) {
            throw new IllegalArgumentException("O Livro Razão exige uma conta analítica.");
        }

        List<MovimentoEscriturado> escriturados = partidas.buscarMovimentosEscriturados(
                empresaId, exercicioId, contexto.exercicio().getDataInicio(), fim, contaId);
        ApuracaoSaldos apuracao = new ApuracaoSaldos(inicio);
        List<LivroRazao.Movimento> movimentos = new ArrayList<>();
        for (MovimentoEscriturado partida : escriturados) {
            LocalDate data = partida.getData();
            apuracao.registrar(data, partida.getTipo(), partida.getValor());
            if (!data.isBefore(inicio)) {
                movimentos.add(new LivroRazao.Movimento(data, partida.getLancamentoId(), partida.getNumero(),
                        partida.getHistoricoLancamento(), partida.getOrigem(), partida.getStatus(),
                        partida.getPartidaId(), partida.getHistoricoPartida(), partida.getTipo(), partida.getValor(),
                        apuracao.saldoAtual()));
            }
        }
        return new LivroRazao(empresaId, exercicioId, inicio, fim,
                new LivroRazao.Conta(contaId, conta.getCodigo(), conta.getNome(), conta.getNatureza()),
                movimentos, apuracao.resumo());
    }
}
