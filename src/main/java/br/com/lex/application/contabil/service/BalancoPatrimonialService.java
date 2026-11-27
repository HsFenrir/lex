package br.com.lex.application.contabil.service;

import br.com.lex.application.contabil.dto.Balancete;
import br.com.lex.application.contabil.dto.Balancete.Conta;
import br.com.lex.application.contabil.dto.BalancoPatrimonial;
import br.com.lex.application.contabil.dto.BalancoPatrimonial.Linha;
import br.com.lex.application.contabil.dto.BalancoPatrimonial.Secao;
import br.com.lex.domain.contabil.enums.ClassificacaoBP;
import br.com.lex.domain.contabil.enums.GrupoConta;
import br.com.lex.domain.contabil.enums.TipoConta;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static java.util.stream.Collectors.toMap;

@Service
@RequiredArgsConstructor
public class BalancoPatrimonialService {

    private final BalanceteService balanceteService;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public BalancoPatrimonial emitir(UUID empresaId, UUID usuarioId, UUID exercicioId, LocalDate dataBase) {
        Balancete balancete = balanceteService.emitir(empresaId, usuarioId, exercicioId, dataBase, dataBase);
        List<Conta> contas = balancete.linhas().stream().map(Balancete.Linha::conta).toList();
        Map<UUID, Conta> plano = contas.stream().collect(toMap(Conta::id, conta -> conta));
        Map<ClassificacaoBP, Map<UUID, BigDecimal>> saldos = new EnumMap<>(ClassificacaoBP.class);
        Map<ClassificacaoBP, BigDecimal> totais = new EnumMap<>(ClassificacaoBP.class);
        BigDecimal resultadoNaoEncerrado = BigDecimal.ZERO;

        for (Balancete.Linha linha : balancete.linhas()) {
            Conta conta = linha.conta();
            BigDecimal saldo = linha.saldos().saldoFinal().valor();
            if (conta.tipo() != TipoConta.ANALITICA || saldo.signum() == 0) {
                continue;
            }
            if (ClassificacaoDemonstracoes.contaDeResultado(conta)) {
                resultadoNaoEncerrado = resultadoNaoEncerrado.subtract(saldo);
                continue;
            }

            ClassificacaoBP classe = ClassificacaoDemonstracoes.classificarBP(conta, plano);
            totais.merge(classe, apresentar(conta, saldo), BigDecimal::add);
            Map<UUID, BigDecimal> secao = saldos.computeIfAbsent(classe, ignorada -> new HashMap<>());
            secao.put(conta.id(), saldo);
            for (Conta pai = plano.get(conta.contaPaiId()); pai != null && pai.grupo() == conta.grupo();
                 pai = plano.get(pai.contaPaiId())) {
                if (pai.classificacaoBP() == classe) {
                    secao.merge(pai.id(), saldo, BigDecimal::add);
                }
            }
        }

        List<Secao> secoes = java.util.Arrays.stream(ClassificacaoBP.values())
                .map(classe -> montarSecao(classe, contas, saldos.getOrDefault(classe, Map.of()), total(totais, classe)))
                .toList();
        BigDecimal ativo = total(totais, ClassificacaoBP.ATIVO_CIRCULANTE)
                .add(total(totais, ClassificacaoBP.ATIVO_NAO_CIRCULANTE));
        BigDecimal passivo = total(totais, ClassificacaoBP.PASSIVO_CIRCULANTE)
                .add(total(totais, ClassificacaoBP.PASSIVO_NAO_CIRCULANTE));
        BigDecimal plEscriturado = total(totais, ClassificacaoBP.PATRIMONIO_LIQUIDO);
        BigDecimal patrimonioLiquido = plEscriturado.add(resultadoNaoEncerrado);
        BigDecimal diferenca = ativo.subtract(passivo).subtract(patrimonioLiquido);

        return new BalancoPatrimonial(balancete.empresa(), balancete.exercicio(), dataBase, secoes,
                ativo, passivo, plEscriturado, resultadoNaoEncerrado, patrimonioLiquido, diferenca,
                diferenca.signum() == 0);
    }

    private Secao montarSecao(ClassificacaoBP classe, List<Conta> plano, Map<UUID, BigDecimal> saldos, BigDecimal total) {
        List<Linha> linhas = plano.stream().filter(conta -> saldos.containsKey(conta.id()))
                .map(conta -> new Linha(conta, saldos.get(conta.id()), apresentar(conta, saldos.get(conta.id()))))
                .toList();
        return new Secao(classe, linhas, total);
    }

    private BigDecimal total(Map<ClassificacaoBP, BigDecimal> totais, ClassificacaoBP classe) {
        return totais.getOrDefault(classe, BigDecimal.ZERO);
    }

    private BigDecimal apresentar(Conta conta, BigDecimal saldo) {
        return conta.grupo() == GrupoConta.ATIVO ? saldo : saldo.negate();
    }
}
