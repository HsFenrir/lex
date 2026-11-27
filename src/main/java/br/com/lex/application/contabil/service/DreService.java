package br.com.lex.application.contabil.service;

import br.com.lex.application.contabil.dto.Balancete;
import br.com.lex.application.contabil.dto.Balancete.Conta;
import br.com.lex.application.contabil.dto.Dre;
import br.com.lex.application.contabil.dto.Dre.ContaResultado;
import br.com.lex.application.contabil.dto.Dre.Linha;
import br.com.lex.application.contabil.dto.Dre.Rubrica;
import br.com.lex.application.contabil.dto.Dre.Valores;
import br.com.lex.domain.contabil.ApuracaoSaldos;
import br.com.lex.domain.contabil.entity.Empresa;
import br.com.lex.domain.contabil.entity.ExercicioContabil;
import br.com.lex.domain.contabil.enums.ClassificacaoDRE;
import br.com.lex.domain.contabil.enums.OrigemLancamento;
import br.com.lex.domain.contabil.enums.TipoConta;
import br.com.lex.domain.contabil.repository.ContaContabilRepository;
import br.com.lex.domain.contabil.repository.PartidaContabilRepository;
import br.com.lex.domain.contabil.repository.PartidaContabilRepository.TotaisPorOrigem;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static java.util.stream.Collectors.toMap;

@Service
@RequiredArgsConstructor
public class DreService {

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final Valores ZERADO = new Valores(ZERO, ZERO, ZERO, ZERO);

    private final ConsultaContabil consulta;
    private final ContaContabilRepository contas;
    private final PartidaContabilRepository partidas;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Dre emitir(UUID empresaId, UUID usuarioId, UUID exercicioId, LocalDate inicio, LocalDate fim) {
        ConsultaContabil.Contexto contexto = consulta.validar(empresaId, usuarioId, exercicioId, inicio, fim);
        Empresa empresa = contexto.empresa();
        ExercicioContabil exercicio = contexto.exercicio();
        LocalDate inicioExercicio = exercicio.getDataInicio();
        LocalDate abertura = partidas.buscarDataAberturaComResultado(
                empresaId, exercicioId, inicioExercicio, exercicio.getDataFim()).orElse(null);
        boolean incluiAbertura = inicio.equals(inicioExercicio) && abertura != null && !fim.isBefore(abertura);
        boolean semDetalhamento = abertura != null && !inicio.isAfter(abertura) && !incluiAbertura;

        List<Conta> estrutura = contas.buscarEstruturaDoPlano(empresaId).stream().map(Conta::daProjecao).toList();
        Map<UUID, Conta> plano = estrutura.stream().collect(toMap(Conta::id, conta -> conta));
        Map<UUID, Valores> porConta = new HashMap<>();
        for (TotaisPorOrigem movimento : partidas.somarMovimentosDeResultadoPorOrigem(
                empresaId, exercicioId, inicioExercicio, fim, inicio)) {
            Conta conta = plano.get(movimento.getContaId());
            if (conta == null || conta.tipo() != TipoConta.ANALITICA || !ClassificacaoDemonstracoes.contaDeResultado(conta)) {
                throw new IllegalStateException("Movimento de resultado sem conta analítica correspondente no plano.");
            }
            porConta.merge(conta.id(), valores(movimento, inicio), Valores::somar);
        }

        List<ContaResultado> contasResultado = new ArrayList<>();
        Map<Rubrica, Valores> rubricas = new EnumMap<>(Rubrica.class);
        for (Conta conta : estrutura) {
            Valores valores = porConta.get(conta.id());
            if (valores != null) {
                ClassificacaoDRE classe = ClassificacaoDemonstracoes.classificarDRE(conta, plano);
                contasResultado.add(new ContaResultado(conta, classe, valores));
                rubricas.merge(rubrica(classe), valores, Valores::somar);
            }
        }
        List<Linha> linhas = montarLinhas(rubricas, incluiAbertura);
        return new Dre(new Balancete.Empresa(empresaId, empresa.getRazaoSocial(), empresa.getCnpj()),
                new Balancete.Exercicio(exercicioId, exercicio.getAno(), inicioExercicio, exercicio.getDataFim()),
                inicio, fim, linhas, contasResultado, abertura, semDetalhamento, incluiAbertura);
    }

    private Valores valores(TotaisPorOrigem movimento, LocalDate inicio) {
        ApuracaoSaldos apuracao = new ApuracaoSaldos(inicio);
        apuracao.registrarTotais(movimento.getTipo(), movimento.getTotalAnterior(), movimento.getTotalPeriodo());
        ApuracaoSaldos.Resumo saldo = apuracao.resumo();
        BigDecimal periodo = saldo.totalCreditos().subtract(saldo.totalDebitos());
        BigDecimal acumulado = saldo.saldoFinal().valor().negate();

        // Classificar também a origem do original permite excluir o encerramento e seus estornos quando houver essa origem.
        OrigemLancamento origem = movimento.getOrigem();
        OrigemLancamento origemEfetiva = origem == OrigemLancamento.ESTORNO ? movimento.getOrigemOriginal() : origem;
        return switch (origemEfetiva) {
            case ABERTURA -> origem == OrigemLancamento.ESTORNO
                    ? new Valores(ZERO, ZERO, periodo, acumulado)
                    : new Valores(ZERO, periodo, ZERO, acumulado);
            case MANUAL, ESTOQUE, ATIVO, DEPRECIACAO, ESTORNO -> new Valores(periodo, ZERO, ZERO, acumulado);
        };
    }

    private Rubrica rubrica(ClassificacaoDRE classe) {
        return switch (classe) {
            case RECEITA_BRUTA -> Rubrica.RECEITA_BRUTA;
            case DEDUCOES_RECEITA -> Rubrica.DEDUCOES_RECEITA;
            case CUSTOS -> Rubrica.CUSTOS;
            case DESPESAS_OPERACIONAIS -> Rubrica.DESPESAS_OPERACIONAIS;
            case RESULTADO_FINANCEIRO -> Rubrica.RESULTADO_FINANCEIRO;
            case OUTRAS_RECEITAS -> Rubrica.OUTRAS_RECEITAS;
            case OUTRAS_DESPESAS -> Rubrica.OUTRAS_DESPESAS;
            case TRIBUTOS_SOBRE_RESULTADO -> Rubrica.TRIBUTOS_SOBRE_RESULTADO;
        };
    }

    private List<Linha> montarLinhas(Map<Rubrica, Valores> rubricas, boolean incluiAbertura) {
        rubricas.put(Rubrica.RECEITA_LIQUIDA, somar(rubricas, Rubrica.RECEITA_BRUTA, Rubrica.DEDUCOES_RECEITA));
        rubricas.put(Rubrica.RESULTADO_BRUTO, somar(rubricas, Rubrica.RECEITA_LIQUIDA, Rubrica.CUSTOS));
        rubricas.put(Rubrica.RESULTADO_OPERACIONAL,
                somar(rubricas, Rubrica.RESULTADO_BRUTO, Rubrica.DESPESAS_OPERACIONAIS));
        rubricas.put(Rubrica.RESULTADO_ANTES_DOS_TRIBUTOS,
                somar(rubricas, Rubrica.RESULTADO_OPERACIONAL, Rubrica.RESULTADO_FINANCEIRO,
                        Rubrica.OUTRAS_RECEITAS, Rubrica.OUTRAS_DESPESAS));
        rubricas.put(Rubrica.RESULTADO_LIQUIDO,
                somar(rubricas, Rubrica.RESULTADO_ANTES_DOS_TRIBUTOS, Rubrica.TRIBUTOS_SOBRE_RESULTADO));

        return Arrays.stream(Rubrica.values()).map(rubrica -> {
            Valores valores = rubricas.getOrDefault(rubrica, ZERADO);
            BigDecimal demonstrado = valores.movimentosEconomicos().add(valores.estornosDeAbertura());
            if (incluiAbertura) {
                demonstrado = demonstrado.add(valores.aberturaImportada());
            }
            return new Linha(rubrica, valores, demonstrado);
        }).toList();
    }

    private Valores somar(Map<Rubrica, Valores> rubricas, Rubrica... parcelas) {
        Valores soma = ZERADO;
        for (Rubrica parcela : parcelas) {
            soma = soma.somar(rubricas.getOrDefault(parcela, ZERADO));
        }
        return soma;
    }
}
