package br.com.lex.application.contabil.service;

import br.com.lex.application.contabil.dto.Balancete;
import br.com.lex.domain.contabil.ApuracaoSaldos;
import br.com.lex.domain.contabil.ApuracaoSaldos.Resumo;
import br.com.lex.domain.contabil.entity.Empresa;
import br.com.lex.domain.contabil.entity.ExercicioContabil;
import br.com.lex.domain.contabil.enums.TipoConta;
import br.com.lex.domain.contabil.repository.ContaContabilRepository;
import br.com.lex.domain.contabil.repository.ContaContabilRepository.ContaDoPlano;
import br.com.lex.domain.contabil.repository.PartidaContabilRepository;
import br.com.lex.domain.contabil.repository.PartidaContabilRepository.TotaisEscriturados;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BalanceteService {

    private static final BigDecimal ZERO = new BigDecimal("0.00");

    private final ConsultaContabil consulta;
    private final ContaContabilRepository contas;
    private final PartidaContabilRepository partidas;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Balancete emitir(UUID empresaId, UUID usuarioId, UUID exercicioId, LocalDate inicio, LocalDate fim) {
        ConsultaContabil.Contexto contexto = consulta.validar(empresaId, usuarioId, exercicioId, inicio, fim);
        Empresa empresa = contexto.empresa();
        ExercicioContabil exercicio = contexto.exercicio();
        List<Balancete.Conta> plano = contas.buscarEstruturaDoPlano(empresaId).stream()
                .map(this::mapearConta)
                .toList();

        Map<UUID, ApuracaoSaldos> apuracoes = new HashMap<>();
        for (Balancete.Conta conta : plano) {
            if (conta.tipo() == TipoConta.ANALITICA) {
                apuracoes.put(conta.id(), new ApuracaoSaldos(inicio));
            }
        }
        for (TotaisEscriturados movimento : partidas.somarMovimentosEscriturados(
                empresaId, exercicioId, exercicio.getDataInicio(), fim, inicio)) {
            ApuracaoSaldos apuracao = apuracoes.get(movimento.getContaId());
            if (apuracao == null) {
                throw new IllegalStateException("Movimento escriturado sem conta analítica correspondente no plano.");
            }
            apuracao.registrarTotais(movimento.getTipo(), movimento.getTotalAnterior(), movimento.getTotalPeriodo());
        }

        Map<UUID, Resumo> saldos = new HashMap<>();
        Resumo zerado = new ApuracaoSaldos(inicio).resumo();
        for (Balancete.Conta conta : plano) {
            ApuracaoSaldos apuracao = apuracoes.get(conta.id());
            saldos.put(conta.id(), apuracao == null ? zerado : apuracao.resumo());
        }
        Balancete.Totais totais = totalizarAnaliticas(apuracoes.keySet(), saldos);
        agregarSinteticas(plano, saldos);
        List<Balancete.Linha> linhas = plano.stream()
                .map(conta -> new Balancete.Linha(conta, saldos.get(conta.id())))
                .toList();

        return new Balancete(new Balancete.Empresa(empresaId, empresa.getRazaoSocial(), empresa.getCnpj()),
                new Balancete.Exercicio(exercicioId, exercicio.getAno(), exercicio.getDataInicio(), exercicio.getDataFim()),
                inicio, fim, linhas, totais, totais.verificarEquilibrio());
    }

    private Balancete.Conta mapearConta(ContaDoPlano conta) {
        return new Balancete.Conta(conta.getId(), conta.getContaPaiId(), conta.getCodigo(), conta.getNome(),
                conta.getTipo(), conta.getNatureza(), conta.getAtiva(), conta.getGrupo(), conta.getClassificacaoBP(),
                conta.getClassificacaoDRE(), conta.getContaRedutora(), conta.getContaReduzidaId());
    }

    private Balancete.Totais totalizarAnaliticas(Iterable<UUID> analiticas, Map<UUID, Resumo> saldos) {
        BigDecimal anteriorDevedor = ZERO;
        BigDecimal anteriorCredor = ZERO;
        BigDecimal debitos = ZERO;
        BigDecimal creditos = ZERO;
        BigDecimal finalDevedor = ZERO;
        BigDecimal finalCredor = ZERO;
        for (UUID id : analiticas) {
            Resumo saldo = saldos.get(id);
            BigDecimal anterior = saldo.saldoAnterior().valor();
            BigDecimal atual = saldo.saldoFinal().valor();
            anteriorDevedor = anteriorDevedor.add(anterior.max(ZERO));
            anteriorCredor = anteriorCredor.subtract(anterior.min(ZERO));
            debitos = debitos.add(saldo.totalDebitos());
            creditos = creditos.add(saldo.totalCreditos());
            finalDevedor = finalDevedor.add(atual.max(ZERO));
            finalCredor = finalCredor.subtract(atual.min(ZERO));
        }
        return new Balancete.Totais(anteriorDevedor, anteriorCredor, debitos, creditos, finalDevedor, finalCredor);
    }

    private void agregarSinteticas(List<Balancete.Conta> plano, Map<UUID, Resumo> saldos) {
        Map<UUID, Balancete.Conta> porId = new HashMap<>();
        Map<UUID, Integer> filhosPendentes = new HashMap<>();
        for (Balancete.Conta conta : plano) {
            porId.put(conta.id(), conta);
        }
        for (Balancete.Conta conta : plano) {
            if (conta.contaPaiId() != null) {
                Balancete.Conta pai = porId.get(conta.contaPaiId());
                if (pai == null || pai.tipo() != TipoConta.SINTETICA) {
                    throw new IllegalStateException("A conta pai deve ser sintética e pertencer ao plano de contas.");
                }
                filhosPendentes.merge(pai.id(), 1, Integer::sum);
            }
        }

        // Cada conta propaga seu resumo ao pai somente após receber todos os filhos.
        Queue<Balancete.Conta> prontas = new ArrayDeque<>();
        for (Balancete.Conta conta : plano) {
            if (!filhosPendentes.containsKey(conta.id())) {
                prontas.add(conta);
            }
        }
        int processadas = 0;
        while (!prontas.isEmpty()) {
            Balancete.Conta conta = prontas.remove();
            processadas++;
            UUID paiId = conta.contaPaiId();
            if (paiId != null) {
                saldos.merge(paiId, saldos.get(conta.id()), Resumo::somar);
                if (filhosPendentes.merge(paiId, -1, Integer::sum) == 0) {
                    prontas.add(porId.get(paiId));
                }
            }
        }
        if (processadas != plano.size()) {
            throw new IllegalStateException("O plano de contas contém um ciclo na hierarquia.");
        }
    }
}
