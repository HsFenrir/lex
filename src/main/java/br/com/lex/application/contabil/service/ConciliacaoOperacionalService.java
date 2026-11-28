package br.com.lex.application.contabil.service;

import br.com.lex.application.contabil.dto.Balancete;
import br.com.lex.application.contabil.dto.ConciliacaoOperacional;
import br.com.lex.application.contabil.dto.ConciliacaoOperacional.Diagnostico;
import br.com.lex.application.contabil.dto.ConciliacaoOperacional.Item;
import br.com.lex.application.contabil.dto.ConciliacaoOperacional.Modulo;
import br.com.lex.domain.contabil.ApuracaoSaldos;
import br.com.lex.domain.contabil.enums.OrigemLancamento;
import br.com.lex.domain.contabil.enums.TipoConta;
import br.com.lex.domain.contabil.repository.ApropriacaoDepreciacaoRepository;
import br.com.lex.domain.contabil.repository.AtivoPatrimonialRepository;
import br.com.lex.domain.contabil.repository.AtivoPatrimonialRepository.CustoPorConta;
import br.com.lex.domain.contabil.repository.ContaContabilRepository;
import br.com.lex.domain.contabil.repository.MovimentoEstoqueRepository;
import br.com.lex.domain.contabil.repository.PartidaContabilRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ConciliacaoOperacionalService {

    private static final BigDecimal ZERO = new BigDecimal("0.00");

    private final BalanceteService balancete;
    private final ContaContabilRepository contas;
    private final MovimentoEstoqueRepository estoque;
    private final AtivoPatrimonialRepository ativos;
    private final ApropriacaoDepreciacaoRepository depreciacoes;
    private final PartidaContabilRepository partidas;
    private final JdbcTemplate jdbc;

    /** Posição ao fim da data-base, inclusive. Redutoras são apresentadas em sinal credor. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ConciliacaoOperacional conciliar(UUID empresaId, UUID usuarioId, UUID exercicioId, LocalDate dataBase) {
        String isolamento = jdbc.queryForObject("show transaction_isolation", String.class);
        if (!"repeatable read".equals(isolamento) && !"serializable".equals(isolamento)) {
            throw new IllegalStateException("A consulta de conciliação exige REPEATABLE READ ou SERIALIZABLE.");
        }
        return apurar(balancete.emitir(empresaId, usuarioId, exercicioId, dataBase, dataBase));
    }

    // O fechamento já possui o bloqueio exclusivo do exercício e apurou o balancete no mesmo recorte.
    void validarFechamento(Balancete apurado) {
        var divergencias = apurar(apurado).itens().stream()
                .filter(item -> item.diagnostico() != Diagnostico.CONCILIADO
                        && item.diagnostico() != Diagnostico.SEM_NECESSIDADE_DE_CONTROLE)
                .map(item -> "%s %s (%s): %s, contábil %s, operacional %s, diferença %s, pendente de importação %s".formatted(
                        item.conta().codigo(), item.conta().nome(), item.modulo(), item.diagnostico(),
                        item.saldoContabil().toPlainString(), item.saldoOperacional().toPlainString(),
                        item.diferenca().toPlainString(), item.saldoPendenteDeImportacao().toPlainString()))
                .toList();
        if (!divergencias.isEmpty()) {
            throw new IllegalStateException("Conciliação operacional com divergências: " + String.join("; ", divergencias));
        }
    }

    private ConciliacaoOperacional apurar(Balancete apurado) {
        UUID empresaId = apurado.empresa().id();
        LocalDate dataBase = apurado.fim();
        UUID mercadorias = contas.buscarContaDoModeloPadrao(empresaId, EstoqueService.MERCADORIAS).orElse(null);
        UUID imobilizado = contas.buscarContaDoModeloPadrao(empresaId, AtivoPatrimonialService.IMOBILIZADO).orElse(null);
        var posicao = estoque.posicaoAte(empresaId, dataBase);
        var custos = ativos.somarCustosAte(empresaId, dataBase).stream()
                .collect(Collectors.toMap(CustoPorConta::getContaId, c -> c));
        var acumulados = depreciacoes.somarPorRedutoraAte(empresaId, dataBase);
        var saldos = apurarPorOrigem(empresaId, dataBase);

        var analiticasImobilizado = apurado.linhas().stream().map(Balancete.Linha::conta)
                .filter(c -> c.tipo() == TipoConta.ANALITICA && imobilizado != null && imobilizado.equals(c.contaPaiId()))
                .map(Balancete.Conta::id).collect(Collectors.toSet());
        analiticasImobilizado.addAll(custos.keySet());
        var itens = new ArrayList<Item>();
        for (var linha : apurado.linhas()) {
            var conta = linha.conta();
            UUID id = conta.id();
            if (id.equals(mercadorias)) {
                itens.add(comparar(linha, Modulo.ESTOQUE,
                        new PosicaoAuxiliar(posicao.valor(), estoque.somarValorImportadoAte(empresaId, dataBase),
                                posicao.lotes() > 0), saldos));
            } else if (acumulados.containsKey(id) || analiticasImobilizado.contains(conta.contaReduzidaId())) {
                var acumulado = acumulados.get(id);
                itens.add(comparar(linha, Modulo.DEPRECIACAO_ACUMULADA, acumulado == null ? PosicaoAuxiliar.VAZIA
                        : new PosicaoAuxiliar(acumulado.acumulado(), acumulado.importado(), true), saldos));
            } else if (analiticasImobilizado.contains(id) && !conta.contaRedutora()) {
                var custo = custos.get(id);
                itens.add(comparar(linha, Modulo.IMOBILIZADO, custo == null ? PosicaoAuxiliar.VAZIA
                        : new PosicaoAuxiliar(custo.getValor(), custo.getImportado(), true), saldos));
            }
        }
        return new ConciliacaoOperacional(empresaId, apurado.exercicio().id(), dataBase, itens);
    }

    private Map<OrigemLancamento, Map<UUID, ApuracaoSaldos>> apurarPorOrigem(UUID empresaId, LocalDate dataBase) {
        Map<OrigemLancamento, Map<UUID, ApuracaoSaldos>> saldos = new EnumMap<>(OrigemLancamento.class);
        for (var partida : partidas.somarMigracaoEManuaisAte(empresaId, dataBase)) {
            saldos.computeIfAbsent(partida.getOrigem(), origem -> new HashMap<>())
                    .computeIfAbsent(partida.getContaId(), id -> new ApuracaoSaldos(dataBase))
                    .registrarTotais(partida.getTipo(), ZERO, partida.getValor());
        }
        return saldos;
    }

    private BigDecimal saldoPorOrigem(Map<OrigemLancamento, Map<UUID, ApuracaoSaldos>> saldos,
                                      OrigemLancamento origem, UUID contaId) {
        var saldo = saldos.getOrDefault(origem, Map.of()).get(contaId);
        return saldo == null ? ZERO : saldo.saldoAtual().valor();
    }

    private Item comparar(Balancete.Linha linha, Modulo modulo, PosicaoAuxiliar posicao,
                           Map<OrigemLancamento, Map<UUID, ApuracaoSaldos>> saldos) {
        BigDecimal sinal = modulo == Modulo.DEPRECIACAO_ACUMULADA ? BigDecimal.ONE.negate() : BigDecimal.ONE;
        UUID contaId = linha.conta().id();
        BigDecimal contabil = linha.saldos().saldoFinal().valor().multiply(sinal);
        BigDecimal operacional = posicao.valor().setScale(2);
        BigDecimal diferenca = contabil.subtract(operacional);
        BigDecimal abertura = saldoPorOrigem(saldos, OrigemLancamento.ABERTURA, contaId).multiply(sinal);
        BigDecimal manual = saldoPorOrigem(saldos, OrigemLancamento.MANUAL, contaId).multiply(sinal);
        BigDecimal pendente = saldoPendenteDeImportacao(abertura, posicao.importado());
        Diagnostico diagnostico;
        if (pendente.signum() > 0) {
            diagnostico = Diagnostico.SALDO_PENDENTE_DE_IMPORTACAO;
        } else if (diferenca.signum() == 0) {
            diagnostico = posicao.temControle() ? Diagnostico.CONCILIADO : Diagnostico.SEM_NECESSIDADE_DE_CONTROLE;
        } else if (diferenca.compareTo(manual) == 0) {
            diagnostico = Diagnostico.DIVERGENCIA_LANCAMENTO_MANUAL;
        } else {
            diagnostico = Diagnostico.DIVERGENCIA_OPERACIONAL;
        }
        return new Item(modulo, linha.conta(), contabil, operacional, diferenca, diagnostico, pendente, manual);
    }

    static BigDecimal saldoPendenteDeImportacao(BigDecimal abertura, BigDecimal importado) {
        return abertura.subtract(importado).max(ZERO);
    }

    private record PosicaoAuxiliar(BigDecimal valor, BigDecimal importado, boolean temControle) {
        private static final PosicaoAuxiliar VAZIA = new PosicaoAuxiliar(ZERO, ZERO, false);
    }
}
