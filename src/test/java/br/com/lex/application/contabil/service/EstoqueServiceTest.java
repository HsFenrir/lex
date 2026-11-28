package br.com.lex.application.contabil.service;

import br.com.lex.application.contabil.dto.AberturaContabil;
import br.com.lex.application.contabil.dto.AberturaContabil.SaldoPorConta;
import br.com.lex.application.contabil.dto.ConciliacaoOperacional.Diagnostico;
import br.com.lex.application.contabil.dto.ConciliacaoOperacional.Item;
import br.com.lex.application.contabil.dto.Dre.Rubrica;
import br.com.lex.application.contabil.dto.LivroDiario;
import br.com.lex.application.contabil.dto.OperacaoEstoque;
import br.com.lex.application.contabil.dto.OperacaoEstoqueConfirmada;
import br.com.lex.application.contabil.dto.SaldoInicialEstoque;
import br.com.lex.application.contabil.dto.ReversaoOperacional;
import br.com.lex.application.contabil.service.EstoqueService.Contexto;
import br.com.lex.application.contabil.service.EstoqueService.Ficha;
import br.com.lex.application.contabil.service.EstoqueService.Saida;
import br.com.lex.domain.contabil.entity.Empresa;
import br.com.lex.domain.contabil.entity.ExercicioContabil;
import br.com.lex.domain.contabil.entity.PeriodoContabil;
import br.com.lex.domain.contabil.entity.Usuario;
import br.com.lex.domain.contabil.enums.OrigemLancamento;
import br.com.lex.domain.contabil.enums.StatusLancamento;
import br.com.lex.domain.contabil.enums.TipoPartida;
import br.com.lex.domain.contabil.repository.ContaContabilRepository;
import br.com.lex.domain.contabil.repository.MovimentoEstoqueRepository.Alocacao;
import br.com.lex.domain.contabil.repository.MovimentoEstoqueRepository.Lote;
import br.com.lex.domain.contabil.repository.MovimentoEstoqueRepository.Movimento;
import br.com.lex.domain.contabil.repository.MovimentoEstoqueRepository.Tipo;
import jakarta.persistence.EntityManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static java.util.stream.Collectors.toMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class EstoqueServiceTest {

    private static final LocalDate DATA = LocalDate.of(2026, 5, 15);
    private static final String CAIXA = "1.1.01.001";
    private static final String ESTOQUE = "1.1.03.001";
    private static final String RECEITA = "4.1.001";
    private static final String CMV = "5.1.001";
    private static final String BANCOS = "1.1.01.002";
    private static final String CLIENTES = "1.1.02.001";
    private static final String FORNECEDORES = "2.1.01.001";

    @Autowired private EstoqueService estoque;
    @Autowired private LancamentoContabilService lancamentos;
    @Autowired private PlanoContasService planos;
    @Autowired private ContaContabilRepository contas;
    @Autowired private FechamentoContabilService fechamento;
    @Autowired private ConciliacaoOperacionalService conciliacao;
    @Autowired private LivroDiarioService diario;
    @Autowired private LivroRazaoService razao;
    @Autowired private BalanceteService balancete;
    @Autowired private BalancoPatrimonialService balanco;
    @Autowired private DreService dre;
    @Autowired private EntityManager em;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private PlatformTransactionManager transacoes;

    @Test
    @Transactional
    void cadastraEIsolaProdutosPorEmpresa() {
        Cenario c = criarCenario();
        Cenario outra = criarCenario();
        EstoqueService.Produto produto = estoque.listarProdutos(c.empresa(), c.usuario()).getFirst();
        assertThat(produto.codigo()).isEqualTo("SKU-01");
        assertThat(produto.unidade()).isEqualTo("UN");
        assertThat(produto.ativo()).isTrue();
        assertThat(estoque.listarProdutos(outra.empresa(), outra.usuario()))
                .extracting(EstoqueService.Produto::id).containsExactly(outra.produto());
        assertThatThrownBy(() -> estoque.consultar(c.empresa(), outra.usuario(), c.produto()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("não pertence");
        assertThatThrownBy(() -> estoque.consultar(outra.empresa(), outra.usuario(), c.produto()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Produto");
        assertThatThrownBy(() -> estoque.registrarEntrada(
                new Contexto(outra.empresa(), outra.usuario(), outra.exercicio(), outra.periodo(), c.produto(), DATA),
                BigDecimal.ONE, BigDecimal.ONE)).hasMessageContaining("Produto");
        assertThatThrownBy(() -> estoque.cadastrarProduto(c.empresa(), c.usuario(), "sku-01", "Repetido", "UN"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional
    void consomeLotesIntegraisEParciaisNaMesmaSaidaEmOrdemPeps() {
        Cenario c = criarCenario();
        Movimento primeiro = entrada(c, DATA, "3", "2.30");
        Movimento segundo = entrada(c, DATA, "2", "4.10");
        Movimento terceiro = entrada(c, DATA.plusDays(1), "4", "8.00");
        Saida saida = saida(c, DATA.plusDays(1), "4");

        assertThat(saida.movimento().valorTotal()).isEqualByComparingTo("11.00");
        assertThat(saida.alocacoes()).extracting(Alocacao::entradaId).containsExactly(primeiro.id(), segundo.id());
        conferirAlocacao(saida.alocacoes().get(0), "3", "6.90");
        conferirAlocacao(saida.alocacoes().get(1), "1", "4.10");
        Ficha ficha = ficha(c);
        assertThat(ficha.movimentos()).extracting(Movimento::id)
                .containsExactly(primeiro.id(), segundo.id(), terceiro.id(), saida.movimento().id());
        assertThat(ficha.movimentos()).allSatisfy(m -> {
            assertThat(m.lancamentoId()).isNotNull();
            assertThat(m.usuarioId()).isEqualTo(c.usuario());
        });
        assertThat(ficha.saldo().quantidade()).isEqualByComparingTo("5");
        assertThat(ficha.saldo().valor()).isEqualByComparingTo("36.10");
        assertThat(ficha.lotes()).extracting(Lote::entradaId).containsExactly(primeiro.id(), segundo.id(), terceiro.id());
        assertThat(ficha.lotes().getFirst().quantidadeRestante()).isEqualByComparingTo("0");
        assertThat(ficha.lotes().getFirst().valorRestante()).isEqualByComparingTo("0");
        assertThat(ficha.lotes().get(1).custoUnitario()).isEqualByComparingTo("4.10");
        assertThat(ficha.lotes().get(1).quantidadeRestante()).isEqualByComparingTo("1");
        assertThat(ficha.lotes().get(1).valorRestante()).isEqualByComparingTo("4.10");
        assertThat(estoque.consultarAlocacoes(c.empresa(), c.usuario(), c.produto(), saida.movimento().id()))
                .isEqualTo(saida.alocacoes());
    }

    @Test
    @Transactional
    void rateiaCentavosCumulativamenteERejeitaPerdaDePrecisao() {
        Cenario c = criarCenario();
        Movimento entrada = entrada(c, DATA, "0.000003", "5000.00000000");
        assertThat(entrada.valorTotal()).isEqualByComparingTo("0.02");
        Saida primeira = saida(c, DATA, "0.000001");
        Saida segunda = saida(c, DATA, "0.000001");
        Saida ultima = saida(c, DATA, "0.000001");
        conferirAlocacao(primeira.alocacoes().getFirst(), "0.000001", "0.01");
        conferirAlocacao(segunda.alocacoes().getFirst(), "0.000001", "0");
        conferirAlocacao(ultima.alocacoes().getFirst(), "0.000001", "0.01");
        assertThat(ficha(c).saldo().valor()).isEqualByComparingTo("0");
        assertThat(ficha(c).saldo().quantidade()).isEqualByComparingTo("0");

        assertThatThrownBy(() -> entrada(c, DATA, "0.0000001", "5000")).hasMessageContaining("escala");
        assertThatThrownBy(() -> entrada(c, DATA, "1", "1.000000001")).hasMessageContaining("escala");
        assertThatThrownBy(() -> entrada(c, DATA, "10000000000000", "1")).hasMessageContaining("precisão");
        assertThatThrownBy(() -> entrada(c, DATA, "1", "0.00000001")).hasMessageContaining("centavo");
        assertThatThrownBy(() -> saida(c, DATA, "0")).hasMessageContaining("positivo");
        assertThat(ficha(c).movimentos()).hasSize(4);
    }

    @Test
    @Transactional
    void preservaSaldoEAlocacoesAposSaidaInsuficienteEImpedeRetroatividade() {
        Cenario c = criarCenario();
        entrada(c, DATA, "2", "1.00");
        entrada(c, DATA.plusDays(2), "3", "2.00");
        Ficha antes = ficha(c);
        assertThatThrownBy(() -> saida(c, DATA.plusDays(2), "6")).hasMessageContaining("insuficiente");
        assertThatThrownBy(() -> entrada(c, DATA.plusDays(1), "1", "99.00")).hasMessageContaining("última movimentação");
        assertThatThrownBy(() -> saida(c, DATA.plusDays(1), "1")).hasMessageContaining("última movimentação");
        assertThat(ficha(c)).isEqualTo(antes);
        assertThat(jdbc.queryForObject("select count(*) from alocacao_estoque where produto_id = ?", Long.class, c.produto()))
                .isZero();
    }

    @Test
    @Transactional
    void respeitaAberturaCalendarioEProdutoInativo() {
        Cenario c = criarCenario();
        lancamentos.registrarSaldosIniciais(new AberturaContabil(c.empresa(), c.usuario(), c.exercicio(), c.periodo(),
                DATA, "Migração", List.of(partida(c, CAIXA, TipoPartida.DEBITO, BigDecimal.ONE),
                        partida(c, "3.1.001", TipoPartida.CREDITO, BigDecimal.ONE))));
        assertThatThrownBy(() -> entrada(c, DATA.minusDays(1), "1", "1")).hasMessageContaining("anterior à abertura");
        assertThatThrownBy(() -> entrada(c, DATA.plusMonths(1), "1", "1")).hasMessageContaining("dentro do período");
        entrada(c, DATA, "1", "1");
        estoque.alterarAtividade(c.empresa(), c.usuario(), c.produto(), false);
        assertThatThrownBy(() -> saida(c, DATA, "1")).hasMessageContaining("produto deve estar ativo");
        estoque.alterarAtividade(c.empresa(), c.usuario(), c.produto(), true);
        fechamento.encerrarPeriodo(c.empresa(), c.usuario(), c.exercicio(), c.periodo());
        assertThatThrownBy(() -> entrada(c, DATA, "1", "1")).hasMessageContaining("período deve estar aberto");
        assertThat(ficha(c).saldo().quantidade()).isEqualByComparingTo("1");
    }

    @Test
    void exigeTransacaoComLancamentoEstoqueConfirmadoEReverteConsumoAposFalha() {
        comCenario((schema, c) -> {
            Contexto contexto = contexto(c, DATA);
            assertThatThrownBy(() -> estoque.registrarEntrada(contexto, BigDecimal.ONE, BigDecimal.ONE))
                    .isInstanceOf(IllegalTransactionStateException.class);
            assertThatThrownBy(() -> executar(schema, () -> estoque.registrarEntrada(contexto, BigDecimal.ONE, BigDecimal.ONE)))
                    .isInstanceOfSatisfying(DataIntegrityViolationException.class, erro ->
                            assertThat(erro.getMostSpecificCause()).isInstanceOfSatisfying(PSQLException.class, causa -> {
                                assertThat(causa.getSQLState()).isEqualTo("23514");
                                assertThat(causa.getServerErrorMessage()).isNotNull();
                                assertThat(causa.getServerErrorMessage().getConstraint())
                                        .isEqualTo("ck_movimento_estoque_contabilizado");
                            }));
            assertThat(executar(schema, () -> ficha(c)).movimentos()).isEmpty();

            executar(schema, () -> entrada(c, DATA, "5", "3.00"));
            Ficha original = executar(schema, () -> ficha(c));
            assertThatThrownBy(() -> executar(schema, () -> {
                estoque.registrarSaida(contexto, new BigDecimal("4"));
                throw new IllegalStateException("Falha simulada na contabilização da saída.");
            })).hasMessageContaining("Falha simulada");
            assertThat(executar(schema, () -> ficha(c))).isEqualTo(original);
            assertThat(executar(schema, () -> jdbc.queryForObject(
                    "select count(*) from alocacao_estoque", Long.class))).isZero();
        });
    }

    @Test
    void duasSaidasConcorrentesNaoConsomemAsMesmasUnidades() {
        conferirConcorrencia(c -> entrada(c, DATA, "5", "3.00"),
                (c, quantidade) -> saida(c, DATA, quantidade).movimento().valorTotal());
    }

    @Test
    void duasVendasConcorrentesConfirmamSomenteAQueTemSaldo() {
        conferirConcorrencia(c -> estoque.registrarCompra(operacao(c, DATA, "5", "3", CAIXA)),
                (c, quantidade) -> estoque.registrarVenda(operacao(c, DATA, quantidade, "5", CAIXA)).custoEstoque());
    }

    private void conferirConcorrencia(Consumer<Cenario> comprar, BiFunction<Cenario, String, BigDecimal> vender) {
        comCenario((schema, c) -> {
            executar(schema, () -> {
                comprar.accept(c);
                return null;
            });
            var executor = Executors.newFixedThreadPool(2);
            var pidPrimeira = new CompletableFuture<Integer>();
            var pidSegunda = new CompletableFuture<Integer>();
            var liberarCommit = new CountDownLatch(1);
            try {
                var primeira = executor.submit(() -> executar(schema, () -> {
                    BigDecimal custo = vender.apply(c, "4");
                    pidPrimeira.complete(pid());
                    aguardar(liberarCommit);
                    return custo;
                }));
                int bloqueadora = pidPrimeira.get(10, TimeUnit.SECONDS);
                var segunda = executor.submit(() -> executar(schema, () -> {
                    pidSegunda.complete(pid());
                    return vender.apply(c, "4");
                }));
                aguardarBloqueio(bloqueadora, pidSegunda.get(5, TimeUnit.SECONDS));
                liberarCommit.countDown();

                assertThat(primeira.get(10, TimeUnit.SECONDS)).isEqualByComparingTo("12.00");
                assertThatThrownBy(() -> segunda.get(10, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class).hasRootCauseMessage("Estoque insuficiente para a saída.");
                Ficha finalizada = executar(schema, () -> ficha(c));
                assertThat(finalizada.movimentos()).hasSize(2);
                assertThat(finalizada.saldo().quantidade()).isEqualByComparingTo("1");
                assertThat(finalizada.saldo().valor()).isEqualByComparingTo("3.00");
                assertThat(executar(schema, () -> jdbc.queryForObject(
                        "select count(*) from alocacao_estoque", Long.class))).isEqualTo(1L);
                assertThat(executar(schema, () -> jdbc.queryForObject(
                        "select ultimo_numero from sequencia_lancamento", Long.class))).isEqualTo(2L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            } catch (ExecutionException | TimeoutException e) {
                throw new AssertionError(e);
            } finally {
                liberarCommit.countDown();
                executor.shutdownNow();
                try {
                    assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            }
        });
    }

    @Test
    void escrituraComprasEVendasEReconciliaLivrosEDemonstracoes() {
        comCenario((schema, c) -> {
            var compraVista = executar(schema, () -> estoque.registrarCompra(operacao(c, DATA, "3", "2", CAIXA)));
            var compraPrazo = executar(schema, () -> estoque.registrarCompra(operacao(c, DATA, "4", "5", FORNECEDORES)));
            var vendaVista = executar(schema, () -> estoque.registrarVenda(operacao(c, DATA, "4", "9", BANCOS)));
            var vendaPrazo = executar(schema, () -> estoque.registrarVenda(operacao(c, DATA, "1", "10", CLIENTES)));
            executar(schema, () -> {
                var livro = diario.emitir(c.empresa(), c.usuario(), c.exercicio(), DATA, DATA);
                assertThat(livro.lancamentos()).hasSize(4);
                conferirLancamento(c, livro.lancamentos().get(0), compraVista, 1, "3", "6", "6",
                        partida(c, ESTOQUE, TipoPartida.DEBITO, new BigDecimal("6.00")),
                        partida(c, CAIXA, TipoPartida.CREDITO, new BigDecimal("6.00")));
                conferirLancamento(c, livro.lancamentos().get(1), compraPrazo, 2, "4", "20", "20",
                        partida(c, ESTOQUE, TipoPartida.DEBITO, new BigDecimal("20.00")),
                        partida(c, FORNECEDORES, TipoPartida.CREDITO, new BigDecimal("20.00")));
                conferirLancamento(c, livro.lancamentos().get(2), vendaVista, 3, "4", "36", "11",
                        partida(c, BANCOS, TipoPartida.DEBITO, new BigDecimal("36.00")),
                        partida(c, RECEITA, TipoPartida.CREDITO, new BigDecimal("36.00")),
                        partida(c, CMV, TipoPartida.DEBITO, new BigDecimal("11.00")),
                        partida(c, ESTOQUE, TipoPartida.CREDITO, new BigDecimal("11.00")));
                conferirLancamento(c, livro.lancamentos().get(3), vendaPrazo, 4, "1", "10", "5",
                        partida(c, CLIENTES, TipoPartida.DEBITO, new BigDecimal("10.00")),
                        partida(c, RECEITA, TipoPartida.CREDITO, new BigDecimal("10.00")),
                        partida(c, CMV, TipoPartida.DEBITO, new BigDecimal("5.00")),
                        partida(c, ESTOQUE, TipoPartida.CREDITO, new BigDecimal("5.00")));

                var alocacoes = estoque.consultarAlocacoes(c.empresa(), c.usuario(), c.produto(), vendaVista.movimentoId());
                assertThat(alocacoes).extracting(Alocacao::entradaId)
                        .containsExactly(compraVista.movimentoId(), compraPrazo.movimentoId());
                conferirAlocacao(alocacoes.get(0), "3", "6");
                conferirAlocacao(alocacoes.get(1), "1", "5");
                var alocacaoPrazo = estoque.consultarAlocacoes(c.empresa(), c.usuario(), c.produto(), vendaPrazo.movimentoId());
                assertThat(alocacaoPrazo).hasSize(1);
                conferirAlocacao(alocacaoPrazo.getFirst(), "1", "5");

                BigDecimal remanescente = ficha(c).saldo().valor();
                assertThat(remanescente).isEqualByComparingTo("10");
                assertThat(razao.emitir(c.empresa(), c.usuario(), c.exercicio(), c.contas().get(ESTOQUE), DATA, DATA)
                        .saldos().saldoFinal().valor()).isEqualByComparingTo(remanescente);
                assertThat(balancete.emitir(c.empresa(), c.usuario(), c.exercicio(), DATA, DATA).linhas())
                        .filteredOn(linha -> linha.conta().id().equals(c.contas().get(ESTOQUE))).singleElement()
                        .satisfies(linha -> assertThat(linha.saldos().saldoFinal().valor()).isEqualByComparingTo(remanescente));
                var bp = balanco.emitir(c.empresa(), c.usuario(), c.exercicio(), DATA);
                assertThat(bp.equilibrado()).isTrue();
                assertThat(bp.secoes().stream().flatMap(secao -> secao.linhas().stream()))
                        .filteredOn(linha -> linha.conta().id().equals(c.contas().get(ESTOQUE))).singleElement()
                        .satisfies(linha -> assertThat(linha.valorApresentado()).isEqualByComparingTo(remanescente));
                var resultado = dre.emitir(c.empresa(), c.usuario(), c.exercicio(), DATA, DATA);
                assertThat(resultado.linhas()).filteredOn(linha -> linha.rubrica() == Rubrica.RECEITA_BRUTA)
                        .singleElement().satisfies(linha -> assertThat(linha.valorDemonstrado()).isEqualByComparingTo("46"));
                assertThat(resultado.linhas()).filteredOn(linha -> linha.rubrica() == Rubrica.RESULTADO_BRUTO)
                        .singleElement().satisfies(linha -> assertThat(linha.valorDemonstrado()).isEqualByComparingTo("30"));
                return null;
            });
            assertThatThrownBy(() -> executar(schema, () -> lancamentos.criarRascunhoEstorno(
                    c.empresa(), c.usuario(), vendaVista.lancamentoId(), c.exercicio(), c.periodo(), DATA, "Estorno")))
                    .hasMessageContaining("módulo de origem");
        });
    }

    private void conferirLancamento(Cenario c, LivroDiario.Lancamento lancamento, OperacaoEstoqueConfirmada operacao,
                                    long numero, String quantidade, String comercial, String custo, SaldoPorConta... partidas) {
        assertThat(operacao.numeroContabil()).isEqualTo(numero);
        assertThat(operacao.quantidade()).isEqualByComparingTo(quantidade);
        assertThat(operacao.valorComercial()).isEqualByComparingTo(comercial);
        assertThat(operacao.custoEstoque()).isEqualByComparingTo(custo);
        assertThat(lancamento.referencia()).isEqualTo(new LivroDiario.ReferenciaLancamento(
                operacao.lancamentoId(), c.exercicio(), numero));
        assertThat(lancamento.status()).isEqualTo(StatusLancamento.CONFIRMADO);
        assertThat(lancamento.origem()).isEqualTo(OrigemLancamento.ESTOQUE);
        assertThat(lancamento.partidas()).map(p -> new SaldoPorConta(p.contaId(), p.tipo(), p.valor()))
                .containsExactlyInAnyOrder(partidas);
        assertThat(jdbc.queryForObject("""
                select m.lancamento_id from movimento_estoque m join auditoria_evento a on a.entidade_id = m.id
                where m.id = ? and a.entidade = 'movimento_estoque' and a.empresa_id = ? and a.usuario_id = ?
                  and a.acao = case m.tipo when 'ENTRADA' then 'COMPRA' else 'VENDA' end
                  and a.dados_novos->>'lancamentoId' = m.lancamento_id::text
                  and a.dados_novos->>'numeroContabil' = ?
                """, UUID.class, operacao.movimentoId(), c.empresa(), c.usuario(), Long.toString(numero)))
                .isEqualTo(operacao.lancamentoId());
    }

    @Test
    void escrituraReceitaComCustoZeroEPreservaCentavoDoLote() {
        comCenario((schema, c) -> {
            executar(schema, () -> estoque.registrarCompra(operacao(c, DATA, "3", "0.005", CAIXA)));
            var primeira = executar(schema, () -> estoque.registrarVenda(operacao(c, DATA, "1", "0.005", CAIXA)));
            var segunda = executar(schema, () -> estoque.registrarVenda(operacao(c, DATA, "1", "0.005", CAIXA)));
            var ultima = executar(schema, () -> estoque.registrarVenda(operacao(c, DATA, "1", "0.005", CAIXA)));
            assertThat(primeira.custoEstoque()).isEqualByComparingTo("0.01");
            assertThat(ultima.custoEstoque()).isEqualByComparingTo("0.01");
            executar(schema, () -> {
                conferirLancamento(c, diario.emitir(c.empresa(), c.usuario(), c.exercicio(), DATA, DATA).lancamentos().get(2),
                        segunda, 3, "1", "0.01", "0",
                        partida(c, CAIXA, TipoPartida.DEBITO, new BigDecimal("0.01")),
                        partida(c, RECEITA, TipoPartida.CREDITO, new BigDecimal("0.01")));
                assertThat(ficha(c).saldo().valor()).isEqualByComparingTo("0");
                return null;
            });
        });
    }

    @Test
    void validaLimiteComercialEDivideCmvAgregadoEmPartidasValidas() {
        comCenario((schema, c) -> {
            assertThatThrownBy(() -> executar(schema, () -> estoque.registrarCompra(operacao(c, DATA, "1", "0.004", CAIXA))))
                    .hasMessageContaining("centavo");
            assertThatThrownBy(() -> executar(schema, () -> estoque.registrarVenda(operacao(c, DATA, "1", "0.004", CAIXA))))
                    .hasMessageContaining("positivo");
            var acimaDoLimite = operacao(c, DATA, "10000000", "10000000000", CAIXA);
            assertThatThrownBy(() -> executar(schema, () -> estoque.registrarCompra(acimaDoLimite)))
                    .hasMessageContaining("17 dígitos");
            assertThatThrownBy(() -> executar(schema, () -> estoque.registrarVenda(acimaDoLimite)))
                    .hasMessageContaining("17 dígitos");
            var primeiro = executar(schema, () -> estoque.registrarCompra(
                    operacao(c, DATA, "10000000", "9999999999.99999999", CAIXA)));
            var segundo = executar(schema, () -> estoque.registrarCompra(operacao(c, DATA, "1", "1", CAIXA)));
            var venda = executar(schema, () -> estoque.registrarVenda(operacao(c, DATA, "10000001", "1", CAIXA)));
            var lancamento = executar(schema, () -> diario.emitir(c.empresa(), c.usuario(), c.exercicio(), DATA, DATA))
                    .lancamentos().getLast();
            assertThat(primeiro.numeroContabil()).isEqualTo(1L);
            assertThat(venda.custoEstoque()).isEqualByComparingTo(primeiro.custoEstoque().add(segundo.custoEstoque()));
            assertThat(lancamento.partidas()).hasSize(6);
            assertThat(lancamento.totalDebitos()).isEqualByComparingTo(venda.custoEstoque().add(venda.valorComercial()));
            assertThat(lancamento.totalCreditos()).isEqualByComparingTo(lancamento.totalDebitos());
        });
    }

    @Test
    void rejeitaContrapartidasEContextosInvalidosEReverteTodaAEscrituracao() {
        comCenario((schema, c) -> {
            Cenario outra = executar(schema, this::criarCenario);
            var compra = executar(schema, () -> estoque.registrarCompra(operacao(c, DATA, "5", "3", CAIXA)));
            var antes = executar(schema, () -> ficha(c));
            assertThatThrownBy(() -> executar(schema, () -> estoque.registrarVenda(operacao(c, DATA, "6", "5", CAIXA))))
                    .hasMessageContaining("insuficiente");
            assertThatThrownBy(() -> executar(schema, () -> estoque.registrarVenda(operacao(c, DATA, "4", "5", FORNECEDORES))))
                    .hasMessageContaining("contrapartida");
            assertThatThrownBy(() -> executar(schema, () -> estoque.registrarCompra(new OperacaoEstoque(
                    c.empresa(), c.usuario(), c.exercicio(), c.periodo(), c.produto(), DATA, BigDecimal.ONE,
                    BigDecimal.ONE, "Contra outra empresa", outra.contas().get(CAIXA)))))
                    .hasMessageContaining("contrapartida");
            assertThatThrownBy(() -> executar(schema, () -> estoque.registrarCompra(new OperacaoEstoque(
                    c.empresa(), c.usuario(), c.exercicio(), c.periodo(), outra.produto(), DATA, BigDecimal.ONE,
                    BigDecimal.ONE, "Produto de outra empresa", c.contas().get(CAIXA)))))
                    .hasMessageContaining("Produto");
            assertThatThrownBy(() -> executar(schema, () -> {
                estoque.registrarCompra(operacao(c, DATA, "2", "2", CAIXA));
                estoque.registrarVenda(operacao(c, DATA, "4", "5", CAIXA));
                throw new IllegalStateException("Falha após confirmar e auditar.");
            })).hasMessageContaining("Falha após confirmar");
            executar(schema, () -> {
                assertThat(ficha(c)).isEqualTo(antes);
                assertThat(jdbc.queryForObject("select count(*) from alocacao_estoque", Long.class)).isZero();
                assertThat(jdbc.queryForObject("select count(*) from lote_estoque", Long.class)).isEqualTo(1L);
                assertThat(jdbc.queryForObject("select count(*) from lancamento_contabil", Long.class)).isEqualTo(1L);
                assertThat(jdbc.queryForObject("select count(*) from partida_contabil", Long.class)).isEqualTo(2L);
                assertThat(jdbc.queryForObject("select count(*) from auditoria_evento where entidade = 'movimento_estoque'",
                        Long.class)).isEqualTo(1L);
                assertThat(jdbc.queryForObject("select ultimo_numero from sequencia_lancamento", Long.class))
                        .isEqualTo(compra.numeroContabil());
                return null;
            });
        });
    }

    @Test
    void conservaLoteESaldoContabilNaContinuidadePatrimonial() {
        comCenario((schema, c) -> {
            var compra = executar(schema, () -> estoque.registrarCompra(operacao(c, DATA, "5", "3", FORNECEDORES)));
            LocalDate janeiro = LocalDate.of(2027, 1, 1);
            LocalDate dezembro = janeiro.minusDays(1);
            OperacaoEstoque venda = executar(schema, () -> {
                em.persist(new PeriodoContabil(em.getReference(ExercicioContabil.class, c.exercicio()),
                        (short) 12, dezembro.withDayOfMonth(1), dezembro));
                ExercicioContabil proximo = new ExercicioContabil(em.getReference(Empresa.class, c.empresa()),
                        (short) 2027, janeiro, janeiro.plusYears(1).minusDays(1));
                em.persist(proximo);
                PeriodoContabil primeiroPeriodo = new PeriodoContabil(proximo, (short) 1, janeiro, janeiro.plusDays(30));
                em.persist(primeiroPeriodo);
                em.flush();
                return new OperacaoEstoque(c.empresa(), c.usuario(), proximo.getId(), primeiroPeriodo.getId(),
                        c.produto(), janeiro, BigDecimal.ONE, new BigDecimal("5"), "Venda após a continuidade",
                        c.contas().get(CAIXA));
            });
            assertThatThrownBy(() -> executar(schema, () -> estoque.registrarVenda(venda)))
                    .hasMessageContaining("continuidade do exercício anterior");
            executar(schema, () -> fechamento.encerrarPeriodo(c.empresa(), c.usuario(), c.exercicio(), c.periodo()));
            var encerramento = executar(schema, () -> {
                CalendarioTestFixture.preencherEncerradosAteNovembro(jdbc, c.exercicio(), DATA.getMonthValue() + 1);
                return fechamento.encerrarExercicio(c.empresa(), c.usuario(), c.exercicio());
            });
            assertThat(encerramento.exercicioDestinoId()).isEqualTo(venda.exercicioId());
            assertThat(encerramento.lancamentoAberturaId()).isNotNull();
            var confirmada = executar(schema, () -> estoque.registrarVenda(venda));
            assertThat(confirmada.numeroContabil()).isEqualTo(2L);
            assertThat(confirmada.custoEstoque()).isEqualByComparingTo("3");
            var item = conciliarEstoque(schema, c, venda.exercicioId(), janeiro);
            assertThat(item.diagnostico()).isEqualTo(Diagnostico.CONCILIADO);
            assertThat(item.saldoOperacional()).isEqualByComparingTo("12.00");
            executar(schema, () -> {
                assertThat(estoque.consultarAlocacoes(c.empresa(), c.usuario(), c.produto(), confirmada.movimentoId()))
                        .extracting(Alocacao::entradaId).containsExactly(compra.movimentoId());
                BigDecimal saldo = ficha(c).saldo().valor();
                assertThat(saldo).isEqualByComparingTo("12");
                assertThat(razao.emitir(c.empresa(), c.usuario(), venda.exercicioId(), c.contas().get(ESTOQUE),
                        janeiro, janeiro).saldos().saldoFinal().valor()).isEqualByComparingTo(saldo);
                return null;
            });
        });
    }

    @Test
    void respeitaMarcoDeMigracaoCalendarioEOrdemDoEstoque() {
        comCenario((schema, c) -> {
            executar(schema, () -> lancamentos.registrarSaldosIniciais(new AberturaContabil(
                    c.empresa(), c.usuario(), c.exercicio(), c.periodo(), DATA, "Migração",
                    List.of(partida(c, CAIXA, TipoPartida.DEBITO, BigDecimal.ONE),
                            partida(c, "3.1.001", TipoPartida.CREDITO, BigDecimal.ONE)))));
            assertThatThrownBy(() -> executar(schema,
                    () -> estoque.registrarCompra(operacao(c, DATA.minusDays(1), "1", "1", CAIXA))))
                    .hasMessageContaining("anterior à abertura");
            assertThatThrownBy(() -> executar(schema,
                    () -> estoque.registrarCompra(operacao(c, DATA.plusMonths(1), "1", "1", CAIXA))))
                    .hasMessageContaining("dentro do período");
            var compra = executar(schema, () -> estoque.registrarCompra(operacao(c, DATA.plusDays(1), "2", "1", CAIXA)));
            assertThat(compra.numeroContabil()).isEqualTo(2L);
            assertThatThrownBy(() -> executar(schema, () -> estoque.registrarVenda(operacao(c, DATA, "1", "2", CAIXA))))
                    .hasMessageContaining("última movimentação");
            executar(schema, () -> fechamento.encerrarPeriodo(c.empresa(), c.usuario(), c.exercicio(), c.periodo()));
            assertThatThrownBy(() -> executar(schema,
                    () -> estoque.registrarVenda(operacao(c, DATA.plusDays(1), "1", "2", CAIXA))))
                    .hasMessageContaining("período deve estar aberto");
        });
    }

    @Test
    void conciliaEstoqueNaDataBaseAposComprasEVendasPosteriores() {
        comCenario((schema, c) -> {
            Cenario outra = executar(schema, this::criarCenario);
            executar(schema, () -> estoque.registrarCompra(operacao(c, DATA, "3", "0.33333333", CAIXA)));
            executar(schema, () -> estoque.registrarVenda(operacao(c, DATA.plusDays(1), "1", "1", CAIXA)));
            executar(schema, () -> estoque.registrarCompra(operacao(c, DATA.plusDays(2), "1", "9", CAIXA)));

            var antesDaVenda = conciliarEstoque(schema, c, c.exercicio(), DATA);
            assertThat(antesDaVenda.saldoContabil()).isEqualByComparingTo("1.00");
            assertThat(antesDaVenda.saldoOperacional()).isEqualByComparingTo("1.00");
            assertThat(antesDaVenda.diagnostico()).isEqualTo(Diagnostico.CONCILIADO);
            var depoisDaVenda = conciliarEstoque(schema, c, c.exercicio(), DATA.plusDays(1));
            assertThat(depoisDaVenda.saldoContabil()).isEqualByComparingTo("0.67");
            assertThat(depoisDaVenda.saldoOperacional()).isEqualByComparingTo("0.67");
            assertThat(conciliarEstoque(schema, outra, outra.exercicio(), DATA).saldoOperacional()).isZero();
            assertThatThrownBy(() -> executarConsulta(schema, () ->
                    conciliacao.conciliar(c.empresa(), outra.usuario(), c.exercicio(), DATA)))
                    .hasMessageContaining("não pertence ao usuário");
        });
    }

    @Test
    void importaInventarioNaAberturaAposUpgradeDaV12SemDuplicarEscrituracao() {
        comSchema(schema -> {
            migrar(schema, "12");
            Cenario c = executar(schema, this::criarCenario);
            UUID abertura = abrirEstoque(schema, c, "10.00");
            migrar(schema, null);
            var pedido = new SaldoInicialEstoque(c.empresa(), c.usuario(), abertura, List.of(
                    new SaldoInicialEstoque.Lote(c.produto(), BigDecimal.ONE, new BigDecimal("4.00")),
                    new SaldoInicialEstoque.Lote(c.produto(), BigDecimal.ONE, new BigDecimal("6.00"))));
            assertThat(conciliarEstoque(schema, c, c.exercicio(), DATA).diagnostico())
                    .isEqualTo(Diagnostico.SALDO_PENDENTE_DE_IMPORTACAO);
            assertThatThrownBy(() -> executar(schema, () ->
                    fechamento.encerrarPeriodo(c.empresa(), c.usuario(), c.exercicio(), c.periodo())))
                    .hasMessageContaining("pendente de importação 10.00");

            var importados = executar(schema, () -> estoque.importarSaldoInicial(pedido));
            assertThat(importados).extracting(Movimento::lancamentoId).containsExactly(abertura, abertura);
            assertThat(importados).extracting(Movimento::data).containsExactly(DATA, DATA);
            executar(schema, () -> {
                assertThat(jdbc.queryForObject("select count(*) from lancamento_contabil", Long.class)).isEqualTo(1L);
                assertThat(jdbc.queryForObject("""
                        select count(*) from auditoria_evento where acao = 'IMPORTAR_SALDO_INICIAL'
                        and dados_novos->>'lancamentoId' = ?
                        """, Long.class, abertura.toString())).isEqualTo(2L);
                return null;
            });
            assertThatThrownBy(() -> executar(schema, () -> estoque.importarSaldoInicial(pedido)))
                    .hasMessageContaining("já possui lotes");
            assertThatThrownBy(() -> executar(schema, () -> lancamentos.criarRascunhoEstorno(
                    c.empresa(), c.usuario(), abertura, c.exercicio(), c.periodo(), DATA, "Estorno")))
                    .hasMessageContaining("lotes iniciais");

            var venda = executar(schema, () -> estoque.registrarVenda(operacao(c, DATA, "1.5", "10", CAIXA)));
            assertThat(venda.numeroContabil()).isEqualTo(2L);
            assertThat(venda.custoEstoque()).isEqualByComparingTo("7.00");
            var conciliado = conciliarEstoque(schema, c, c.exercicio(), DATA);
            assertThat(conciliado.saldoOperacional()).isEqualByComparingTo("3.00");
            assertThat(conciliado.diagnostico()).isEqualTo(Diagnostico.CONCILIADO);
        });
    }

    @Test
    void rejeitaInventarioDiferenteEReverteLotesEAuditoriaSeFalharNoUltimoLote() {
        comCenario((schema, c) -> {
            UUID abertura = abrirEstoque(schema, c, "10.00");
            var primeiro = new SaldoInicialEstoque.Lote(c.produto(), BigDecimal.ONE, new BigDecimal("4.00"));
            var incompleto = new SaldoInicialEstoque(c.empresa(), c.usuario(), abertura, List.of(primeiro));
            assertThatThrownBy(() -> executar(schema, () -> estoque.importarSaldoInicial(incompleto)))
                    .hasMessageContaining("débito de Mercadorias");

            var completo = new SaldoInicialEstoque(c.empresa(), c.usuario(), abertura, List.of(primeiro,
                    new SaldoInicialEstoque.Lote(c.produto(), BigDecimal.ONE, new BigDecimal("6.00"))));
            executar(schema, () -> {
                jdbc.execute("""
                        alter table auditoria_evento add constraint teste_bloquear_importacao
                        check (acao <> 'IMPORTAR_SALDO_INICIAL' or (dados_novos->>'custoEstoque')::numeric <> 6)
                        """);
                return null;
            });
            assertThatThrownBy(() -> executar(schema, () -> estoque.importarSaldoInicial(completo)))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("teste_bloquear_importacao");
            executar(schema, () -> {
                assertThat(ficha(c).lotes()).isEmpty();
                assertThat(ficha(c).movimentos()).isEmpty();
                assertThat(jdbc.queryForObject("""
                        select count(*) from auditoria_evento where acao = 'IMPORTAR_SALDO_INICIAL'
                        """, Long.class)).isZero();
                assertThat(jdbc.queryForObject("select ultimo_numero from sequencia_lancamento", Long.class)).isEqualTo(1L);
                return null;
            });
        });
    }

    @Test
    void fechamentoMensalBloqueiaDiferencaDeEstoqueEAceitaAposEstornoDoManual() {
        comCenario((schema, c) -> {
            executar(schema, () -> estoque.registrarCompra(operacao(c, DATA, "1", "4", CAIXA)));
            UUID manual = executar(schema, () -> {
                var rascunho = lancamentos.criarRascunho(c.empresa(), c.usuario(), c.exercicio(), c.periodo(),
                        DATA, "Ajuste manual");
                lancamentos.adicionarPartida(c.empresa(), c.usuario(), rascunho.getId(), c.contas().get(ESTOQUE),
                        TipoPartida.DEBITO, new BigDecimal("0.01"), null);
                lancamentos.adicionarPartida(c.empresa(), c.usuario(), rascunho.getId(), c.contas().get(CAIXA),
                        TipoPartida.CREDITO, new BigDecimal("0.01"), null);
                return lancamentos.confirmarLancamento(c.empresa(), c.usuario(), rascunho.getId()).getId();
            });
            var item = conciliarEstoque(schema, c, c.exercicio(), DATA);
            assertThat(item.diagnostico()).isEqualTo(Diagnostico.DIVERGENCIA_LANCAMENTO_MANUAL);
            assertThat(item.diferenca()).isEqualByComparingTo("0.01");
            assertThatThrownBy(() -> executar(schema, () ->
                    fechamento.encerrarPeriodo(c.empresa(), c.usuario(), c.exercicio(), c.periodo())))
                    .hasMessageContaining(ESTOQUE).hasMessageContaining("diferença 0.01");
            executar(schema, () -> {
                var estorno = lancamentos.criarRascunhoEstorno(c.empresa(), c.usuario(), manual, c.exercicio(),
                        c.periodo(), DATA, "Estorno do ajuste");
                return lancamentos.confirmarEstorno(c.empresa(), c.usuario(), estorno.getId());
            });
            assertThat(conciliarEstoque(schema, c, c.exercicio(), DATA).diferenca()).isZero();
            executar(schema, () -> fechamento.encerrarPeriodo(c.empresa(), c.usuario(), c.exercicio(), c.periodo()));
        });
    }

    @Test
    void estornaVendaECompraPreservandoAlocacoesPepsECortesContabeis() {
        comCenario((schema, c) -> {
            UUID abertura = abrirEstoque(schema, c, "10.00");
            var loteInicial = executar(schema, () -> estoque.importarSaldoInicial(pedidoDeImportacao(c, abertura))).getFirst();
            var compra = executar(schema, () -> estoque.registrarCompra(operacao(c, DATA.plusDays(1), "2", "8", CAIXA)));
            LocalDate dataVenda = DATA.plusDays(2);
            var venda = executar(schema, () -> estoque.registrarVenda(operacao(c, dataVenda, "3", "12", CAIXA)));
            var alocacoes = executar(schema, () ->
                    estoque.consultarAlocacoes(c.empresa(), c.usuario(), c.produto(), venda.movimentoId()));
            assertThat(alocacoes).extracting(Alocacao::entradaId).containsExactly(loteInicial.id(), compra.movimentoId());
            conferirAlocacao(alocacoes.getFirst(), "2", "10");
            conferirAlocacao(alocacoes.getLast(), "1", "8");
            assertThatThrownBy(() -> estornar(schema, c, compra.movimentoId(), DATA.plusDays(3)))
                    .hasMessageContaining("última movimentação efetiva");
            assertThatThrownBy(() -> estornar(schema, c, loteInicial.id(), DATA.plusDays(3)))
                    .hasMessageContaining("origem esperada");

            LocalDate dataEstornoVenda = DATA.plusDays(3);
            var estornoVenda = estornar(schema, c, venda.movimentoId(), dataEstornoVenda);
            assertThat(estornoVenda.numeroContabil()).isEqualTo(4L);
            assertThat(executar(schema, () -> ficha(c)).saldo().valor()).isEqualByComparingTo("26");
            assertThat(executar(schema, () -> estoque.consultarAlocacoes(c.empresa(), c.usuario(), c.produto(),
                    venda.movimentoId()))).containsExactlyElementsOf(alocacoes);
            assertThat(conciliarEstoque(schema, c, c.exercicio(), dataVenda).saldoOperacional()).isEqualByComparingTo("8");
            assertThat(conciliarEstoque(schema, c, c.exercicio(), dataEstornoVenda).saldoOperacional()).isEqualByComparingTo("26");
            assertThatThrownBy(() -> estornar(schema, c, venda.movimentoId(), dataEstornoVenda))
                    .hasMessageContaining("original deve estar confirmado");

            LocalDate dataEstornoCompra = DATA.plusDays(4);
            var estornoCompra = estornar(schema, c, compra.movimentoId(), dataEstornoCompra);
            executar(schema, () -> {
                Ficha ficha = ficha(c);
                assertThat(ficha.movimentos()).hasSize(3);
                assertThat(ficha.reversoes()).containsExactly(estornoVenda, estornoCompra);
                assertThat(ficha.lotes()).filteredOn(l -> l.entradaId().equals(compra.movimentoId())).singleElement()
                        .satisfies(l -> {
                            assertThat(l.valorOriginal()).isEqualByComparingTo("16");
                            assertThat(l.valorRestante()).isZero();
                            assertThat(l.quantidadeRestante()).isZero();
                        });
                var diarioEmitido = diario.emitir(c.empresa(), c.usuario(), c.exercicio(), DATA, dataEstornoCompra);
                var original = diarioEmitido.lancamentos().stream()
                        .filter(l -> l.referencia().id().equals(venda.lancamentoId())).findFirst().orElseThrow();
                assertThat(original.status()).isEqualTo(StatusLancamento.ESTORNADO);
                assertThat(diarioEmitido.lancamentos()).filteredOn(l -> l.referencia().id().equals(estornoVenda.lancamentoEstornoId()))
                        .singleElement().satisfies(l -> {
                            assertThat(l.original().id()).isEqualTo(venda.lancamentoId());
                            var esperadas = original.partidas().stream().map(p -> new SaldoPorConta(p.contaId(),
                                    p.tipo() == TipoPartida.DEBITO ? TipoPartida.CREDITO : TipoPartida.DEBITO, p.valor())).toList();
                            assertThat(l.partidas()).map(p -> new SaldoPorConta(p.contaId(), p.tipo(), p.valor()))
                                    .containsExactlyInAnyOrderElementsOf(esperadas);
                        });
                assertThat(razao.emitir(c.empresa(), c.usuario(), c.exercicio(), c.contas().get(ESTOQUE), DATA, dataEstornoCompra)
                        .saldos().saldoFinal().valor()).isEqualByComparingTo("10");
                assertThat(balancete.emitir(c.empresa(), c.usuario(), c.exercicio(), DATA, dataEstornoCompra).linhas())
                        .filteredOn(l -> l.conta().id().equals(c.contas().get(ESTOQUE))).singleElement()
                        .satisfies(l -> assertThat(l.saldos().saldoFinal().valor()).isEqualByComparingTo("10"));
                assertThat(balanco.emitir(c.empresa(), c.usuario(), c.exercicio(), dataEstornoCompra).secoes().stream()
                        .flatMap(s -> s.linhas().stream()).filter(l -> l.conta().id().equals(c.contas().get(ESTOQUE))))
                        .singleElement().satisfies(l -> assertThat(l.valorApresentado()).isEqualByComparingTo("10"));
                assertThat(dre.emitir(c.empresa(), c.usuario(), c.exercicio(), DATA, dataEstornoCompra).linhas())
                        .filteredOn(l -> l.rubrica() == Rubrica.RESULTADO_BRUTO).singleElement()
                        .satisfies(l -> assertThat(l.valorDemonstrado()).isZero());
                assertThat(jdbc.queryForObject("select count(*) from auditoria_evento where acao = 'ESTORNAR'", Long.class))
                        .isEqualTo(2L);
                return null;
            });
            assertThat(conciliarEstoque(schema, c, c.exercicio(), dataEstornoCompra).diagnostico()).isEqualTo(Diagnostico.CONCILIADO);
            assertThatThrownBy(() -> executar(schema, () -> estoque.registrarVenda(operacao(c, dataVenda, "1", "12", CAIXA))))
                    .hasMessageContaining("última movimentação");
            var novaVenda = executar(schema, () -> estoque.registrarVenda(operacao(c, DATA.plusDays(5), "1", "12", CAIXA)));
            assertThat(novaVenda.custoEstoque()).isEqualByComparingTo("5");
        });
    }

    @Test
    void estornaVendaComCmvZeroEReverteTodoEstornoSeAuditoriaFalhar() {
        comCenario((schema, c) -> {
            executar(schema, () -> estoque.registrarCompra(operacao(c, DATA, "3", "0.00333333", CAIXA)));
            var venda = executar(schema, () -> estoque.registrarVenda(operacao(c, DATA, "1", "10", CAIXA)));
            assertThat(venda.custoEstoque()).isZero();
            Ficha antes = executar(schema, () -> ficha(c));
            executar(schema, () -> {
                jdbc.execute("alter table auditoria_evento add constraint teste_rejeitar_estorno check (acao <> 'ESTORNAR')");
                return null;
            });
            assertThatThrownBy(() -> estornar(schema, c, venda.movimentoId(), DATA.plusDays(1)))
                    .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("teste_rejeitar_estorno");
            assertThat(executar(schema, () -> ficha(c))).isEqualTo(antes);
            executar(schema, () -> {
                assertThat(jdbc.queryForObject("select ultimo_numero from sequencia_lancamento", Long.class)).isEqualTo(2L);
                assertThat(jdbc.queryForObject("select count(*) from lancamento_contabil", Long.class)).isEqualTo(2L);
                assertThat(jdbc.queryForObject("select count(*) from partida_contabil", Long.class)).isEqualTo(4L);
                assertThat(jdbc.queryForObject("select count(*) from auditoria_evento where acao = 'ESTORNAR'", Long.class)).isZero();
                jdbc.execute("alter table auditoria_evento drop constraint teste_rejeitar_estorno");
                return null;
            });
            var estorno = estornar(schema, c, venda.movimentoId(), DATA.plusDays(1));
            assertThat(estorno.numeroContabil()).isEqualTo(3L);
            var saldo = executar(schema, () -> ficha(c)).saldo();
            assertThat(saldo.quantidade()).isEqualByComparingTo("3");
            assertThat(saldo.valor()).isEqualByComparingTo("0.01");
            assertThat(executar(schema, () -> jdbc.queryForObject(
                    "select count(*) from partida_contabil where lancamento_id = ?", Long.class, estorno.lancamentoEstornoId())))
                    .isEqualTo(2L);
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void estornoConcorrenteDaCompraSerializaComVendaOuOutroEstorno(boolean outraReversao) {
        comCenario((schema, c) -> {
            var compra = executar(schema, () -> estoque.registrarCompra(operacao(c, DATA, "2", "4", CAIXA)));
            Runnable estornarCompra = () -> estoque.estornarMovimento(c.empresa(), c.usuario(), compra.movimentoId(),
                    c.periodo(), DATA, "Estorno da compra");
            var disputa = disputarComCommit(schema, estornarCompra, () -> {
                if (outraReversao) {
                    estornarCompra.run();
                } else {
                    estoque.registrarVenda(operacao(c, DATA, "1", "9", CAIXA));
                }
                return null;
            }, outraReversao ? "lancamento_contabil" : "produto_estoque");
            assertThatThrownBy(disputa::join).hasRootCauseMessage(outraReversao
                    ? "O lançamento original deve estar confirmado." : "Estoque insuficiente para a saída.");
            var ficha = executar(schema, () -> ficha(c));
            assertThat(ficha.movimentos()).hasSize(1);
            assertThat(ficha.reversoes()).hasSize(1);
            assertThat(ficha.saldo().quantidade()).isZero();
        });
    }

    @Test
    void rejeitaEstornoDeOutraEmpresaOuComCompetenciaOriginalEncerrada() {
        comCenario((schema, c) -> {
            var compra = executar(schema, () -> estoque.registrarCompra(operacao(c, DATA, "2", "4", CAIXA)));
            Cenario outra = executar(schema, this::criarCenario);
            assertThatThrownBy(() -> estornar(schema, outra, compra.movimentoId(), DATA))
                    .hasMessageContaining("Movimento não encontrado na empresa");
            var junho = LocalDate.of(2026, 6, 1);
            UUID destino = executar(schema, () -> {
                var periodo = new PeriodoContabil(em.getReference(ExercicioContabil.class, c.exercicio()),
                        (short) 6, junho, junho.withDayOfMonth(30));
                em.persist(periodo);
                em.flush();
                return periodo.getId();
            });
            executar(schema, () -> fechamento.encerrarPeriodo(c.empresa(), c.usuario(), c.exercicio(), c.periodo()));
            assertThatThrownBy(() -> executar(schema, () -> estoque.estornarMovimento(c.empresa(), c.usuario(),
                    compra.movimentoId(), destino, junho, "Estorno da compra")))
                    .hasMessageContaining("período deve estar aberto");
            assertThat(executar(schema, () -> ficha(c)).reversoes()).isEmpty();
        });
    }

    private ReversaoOperacional estornar(String schema, Cenario c, UUID movimento, LocalDate data) {
        return executar(schema, () -> estoque.estornarMovimento(c.empresa(), c.usuario(), movimento,
                c.periodo(), data, "Estorno operacional"));
    }

    @Test
    void protegeSaldoInicialMesmoComAjusteManualEProdutoDiferente() {
        comCenario((schema, c) -> {
            UUID abertura = abrirEstoque(schema, c, "10.00");
            UUID outroProduto = executar(schema, () ->
                    estoque.cadastrarProduto(c.empresa(), c.usuario(), "SKU-02", "Outra mercadoria", "UN")).id();
            var compra = operacao(c, DATA.plusDays(1), "2", "8", CAIXA);
            var venda = operacao(c, DATA.plusDays(1), "1", "12", CAIXA);
            var compraOutroProduto = new OperacaoEstoque(c.empresa(), c.usuario(), c.exercicio(), c.periodo(),
                    outroProduto, compra.data(), compra.quantidade(), compra.valorUnitario(), compra.historico(),
                    compra.contrapartidaId());
            assertThatThrownBy(() -> executar(schema, () -> estoque.registrarCompra(compra)))
                    .hasMessageContaining("Mercadorias pendente de importação: 10.00");
            assertThatThrownBy(() -> executar(schema, () -> estoque.registrarVenda(venda)))
                    .hasMessageContaining("Importe os lotes da abertura");
            assertThatThrownBy(() -> executar(schema, () -> estoque.registrarCompra(compraOutroProduto)))
                    .hasMessageContaining("Mercadorias pendente de importação");
            conferirSemEscrituracaoOperacional(schema, 1L);

            executar(schema, () -> {
                var manual = lancamentos.criarRascunho(c.empresa(), c.usuario(), c.exercicio(), c.periodo(),
                        DATA, "Ajuste manual");
                lancamentos.adicionarPartida(c.empresa(), c.usuario(), manual.getId(), c.contas().get(CAIXA),
                        TipoPartida.DEBITO, new BigDecimal("10.00"), null);
                lancamentos.adicionarPartida(c.empresa(), c.usuario(), manual.getId(), c.contas().get(ESTOQUE),
                        TipoPartida.CREDITO, new BigDecimal("10.00"), null);
                return lancamentos.confirmarLancamento(c.empresa(), c.usuario(), manual.getId());
            });
            assertThat(conciliarEstoque(schema, c, c.exercicio(), DATA).diferenca()).isZero();
            assertThatThrownBy(() -> executar(schema, () -> estoque.registrarCompra(compra)))
                    .hasMessageContaining("Mercadorias pendente de importação: 10.00");
            conferirSemEscrituracaoOperacional(schema, 2L);

            Cenario outra = executar(schema, this::criarCenario);
            assertThat(executar(schema, () -> estoque.registrarCompra(operacao(outra, DATA, "1", "3", CAIXA)))
                    .numeroContabil()).isEqualTo(1L);
            assertThat(executar(schema, () -> estoque.registrarVenda(operacao(outra, DATA, "1", "5", CAIXA)))
                    .custoEstoque()).isEqualByComparingTo("3.00");

            var lote = executar(schema, () -> estoque.importarSaldoInicial(pedidoDeImportacao(c, abertura))).getFirst();
            assertThat(executar(schema, () -> estoque.registrarCompra(compra)).numeroContabil()).isEqualTo(3L);
            var vendaConfirmada = executar(schema, () -> estoque.registrarVenda(venda));
            assertThat(vendaConfirmada.numeroContabil()).isEqualTo(4L);
            assertThat(vendaConfirmada.custoEstoque()).isEqualByComparingTo("5.00");
            assertThat(executar(schema, () -> estoque.consultarAlocacoes(c.empresa(), c.usuario(), c.produto(),
                    vendaConfirmada.movimentoId()))).extracting(Alocacao::entradaId).containsExactly(lote.id());

        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void compraOuVendaAguardaImportacaoNoProdutoERevalidaSaldo(boolean venda) {
        comCenario((schema, c) -> {
            UUID abertura = abrirEstoque(schema, c, "10.00");
            var pedido = pedidoDeImportacao(c, abertura);
            var operacao = operacao(c, DATA, "1", "8", CAIXA);
            var concorrente = disputarComCommit(schema, () -> estoque.importarSaldoInicial(pedido),
                    () -> venda ? estoque.registrarVenda(operacao) : estoque.registrarCompra(operacao), "produto_estoque");
            var confirmada = concorrente.join();
            assertThat(confirmada.numeroContabil()).isEqualTo(2L);
            assertThat(confirmada.custoEstoque()).isEqualByComparingTo(venda ? "5.00" : "8.00");
        });
    }

    @Test
    void compraRevalidaSaldoDeAberturaConfirmadaEnquantoAguardaSequencia() {
        comCenario((schema, c) -> {
            var compra = operacao(c, DATA, "1", "8", CAIXA);
            var concorrente = disputarComCommit(schema, () -> lancamentos.registrarSaldosIniciais(aberturaEstoque(c, "10.00")),
                    () -> estoque.registrarCompra(compra), "sequencia_lancamento");
            assertThatThrownBy(concorrente::join)
                    .hasRootCauseMessage("Saldo inicial de Mercadorias pendente de importação: 10.00. "
                            + "Importe os lotes da abertura antes de comprar ou vender.");
            conferirSemEscrituracaoOperacional(schema, 1L);
        });
    }

    @Test
    void aberturaNaoUltrapassaCompraQueObteveAPrimeiraNumeracao() {
        comCenario((schema, c) -> {
            var compra = operacao(c, DATA, "1", "8", CAIXA);
            var concorrente = disputarComCommit(schema, () -> estoque.registrarCompra(compra),
                    () -> lancamentos.registrarSaldosIniciais(aberturaEstoque(c, "10.00")), "sequencia_lancamento");
            assertThatThrownBy(concorrente::join).hasRootCauseMessage(
                    "A abertura deve ser o primeiro lançamento escriturado no exercício.");
            executar(schema, () -> {
                assertThat(jdbc.queryForObject("select ultimo_numero from sequencia_lancamento", Long.class)).isEqualTo(1L);
                assertThat(jdbc.queryForObject("select count(*) from lancamento_contabil", Long.class)).isEqualTo(1L);
                assertThat(ficha(c).movimentos()).hasSize(1);
                return null;
            });
        });
    }

    private SaldoInicialEstoque pedidoDeImportacao(Cenario c, UUID abertura) {
        return new SaldoInicialEstoque(c.empresa(), c.usuario(), abertura,
                List.of(new SaldoInicialEstoque.Lote(c.produto(), new BigDecimal("2"), new BigDecimal("5"))));
    }

    private void conferirSemEscrituracaoOperacional(String schema, long numero) {
        executar(schema, () -> {
            assertThat(jdbc.queryForObject("select ultimo_numero from sequencia_lancamento", Long.class)).isEqualTo(numero);
            assertThat(jdbc.queryForObject("select count(*) from lancamento_contabil", Long.class)).isEqualTo(numero);
            assertThat(jdbc.queryForObject("select count(*) from partida_contabil", Long.class)).isEqualTo(2L * numero);
            assertThat(jdbc.queryForObject("select count(*) from movimento_estoque", Long.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from lote_estoque", Long.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from alocacao_estoque", Long.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from auditoria_evento where entidade = 'movimento_estoque'",
                    Long.class)).isZero();
            return null;
        });
    }

    private UUID abrirEstoque(String schema, Cenario c, String valor) {
        return executar(schema, () -> lancamentos.registrarSaldosIniciais(aberturaEstoque(c, valor))).lancamentoId();
    }

    private AberturaContabil aberturaEstoque(Cenario c, String valor) {
        return new AberturaContabil(
                c.empresa(), c.usuario(), c.exercicio(), c.periodo(), DATA, "Migração",
                List.of(partida(c, ESTOQUE, TipoPartida.DEBITO, new BigDecimal(valor)),
                        partida(c, "3.1.001", TipoPartida.CREDITO, new BigDecimal(valor))));
    }

    private Item conciliarEstoque(String schema, Cenario c, UUID exercicio, LocalDate data) {
        return executarConsulta(schema, () -> conciliacao.conciliar(c.empresa(), c.usuario(), exercicio, data)
                .itens().stream().filter(item -> item.conta().id().equals(c.contas().get(ESTOQUE)))
                .findFirst().orElseThrow());
    }

    private <T> T executarConsulta(String schema, Supplier<T> operacao) {
        var tx = new TransactionTemplate(transacoes);
        tx.setReadOnly(true);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return tx.execute(status -> {
            jdbc.execute("set local search_path to " + schema);
            return operacao.get();
        });
    }

    private OperacaoEstoque operacao(Cenario c, LocalDate data, String quantidade, String unitario, String contrapartida) {
        return new OperacaoEstoque(c.empresa(), c.usuario(), c.exercicio(), c.periodo(), c.produto(), data,
                new BigDecimal(quantidade), new BigDecimal(unitario), "Movimentação de mercadoria", c.contas().get(contrapartida));
    }

    @Test
    void aplicaV8SobreV7() {
        comSchema(schema -> {
            migrar(schema, "7");
            migrar(schema, null);
            executar(schema, () -> {
                Cenario c = criarCenario();
                assertThat(ficha(c).saldo().quantidade()).isEqualByComparingTo("0");
                assertThat(jdbc.queryForObject("""
                        select count(*) from flyway_schema_history where version = '8' and success
                        """, Long.class)).isEqualTo(1L);
                return null;
            });
        });
    }

    private Movimento entrada(Cenario c, LocalDate data, String quantidade, String custo) {
        Movimento entrada = estoque.registrarEntrada(contexto(c, data), new BigDecimal(quantidade), new BigDecimal(custo));
        vincularFixture(c, entrada);
        return entrada;
    }

    private Saida saida(Cenario c, LocalDate data, String quantidade) {
        Saida saida = estoque.registrarSaida(contexto(c, data), new BigDecimal(quantidade));
        vincularFixture(c, saida.movimento());
        return saida;
    }

    // Fixture que satisfaz o vínculo obrigatório sem expor no 7A um fluxo de compra ou venda.
    private void vincularFixture(Cenario c, Movimento movimento) {
        BigDecimal custo = movimento.valorTotal();
        List<SaldoPorConta> partidas = movimento.tipo() == Tipo.ENTRADA
                ? List.of(partida(c, ESTOQUE, TipoPartida.DEBITO, custo), partida(c, CAIXA, TipoPartida.CREDITO, custo))
                : custo.signum() == 0
                    ? List.of(partida(c, CAIXA, TipoPartida.DEBITO, BigDecimal.ONE),
                            partida(c, RECEITA, TipoPartida.CREDITO, BigDecimal.ONE))
                    : List.of(partida(c, CMV, TipoPartida.DEBITO, custo), partida(c, ESTOQUE, TipoPartida.CREDITO, custo));
        UUID lancamento = lancamentos.escriturarTecnico(em.getReference(Empresa.class, c.empresa()),
                em.getReference(Usuario.class, c.usuario()), em.getReference(ExercicioContabil.class, c.exercicio()),
                em.getReference(PeriodoContabil.class, c.periodo()), movimento.data(), "Fixture de estoque",
                OrigemLancamento.ESTOQUE, partidas).getId();
        estoque.vincularLancamento(movimento, lancamento);
    }

    private SaldoPorConta partida(Cenario c, String codigo, TipoPartida tipo, BigDecimal valor) {
        return new SaldoPorConta(c.contas().get(codigo), tipo, valor);
    }

    private Ficha ficha(Cenario c) {
        return estoque.consultar(c.empresa(), c.usuario(), c.produto());
    }

    private Contexto contexto(Cenario c, LocalDate data) {
        return new Contexto(c.empresa(), c.usuario(), c.exercicio(), c.periodo(), c.produto(), data);
    }

    private void conferirAlocacao(Alocacao alocacao, String quantidade, String valor) {
        assertThat(alocacao.quantidade()).isEqualByComparingTo(quantidade);
        assertThat(alocacao.valor()).isEqualByComparingTo(valor);
    }

    private Cenario criarCenario() {
        Usuario usuario = new Usuario("Titular", UUID.randomUUID() + "@example.test", "hash-teste");
        em.persist(usuario);
        Empresa empresa = new Empresa(usuario, "Loja", "Loja", null);
        em.persist(empresa);
        ExercicioContabil exercicio = new ExercicioContabil(empresa, (short) 2026,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
        em.persist(exercicio);
        PeriodoContabil periodo = new PeriodoContabil(exercicio, (short) 5, DATA.withDayOfMonth(1), DATA.withDayOfMonth(31));
        em.persist(periodo);
        em.flush();
        UUID plano = planos.provisionar(empresa.getId(), usuario.getId()).getId();
        UUID produto = estoque.cadastrarProduto(empresa.getId(), usuario.getId(), " sku-01 ", "Mercadoria", " un ").id();
        return new Cenario(empresa.getId(), usuario.getId(), exercicio.getId(), periodo.getId(), produto,
                contas.findAllByPlanoContasIdOrderByCodigoAsc(plano).stream()
                        .collect(toMap(c -> c.getCodigo(), c -> c.getId())));
    }

    private void comCenario(BiConsumer<String, Cenario> teste) {
        comSchema(schema -> {
            migrar(schema, null);
            teste.accept(schema, executar(schema, this::criarCenario));
        });
    }

    private void comSchema(Consumer<String> teste) {
        String schema = "lex_estoque_" + UUID.randomUUID().toString().replace("-", "");
        try {
            teste.accept(schema);
        } finally {
            jdbc.execute("drop schema if exists " + schema + " cascade");
        }
    }

    private void migrar(String schema, String alvo) {
        var configuracao = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration");
        if (alvo != null) {
            configuracao.target(alvo);
        }
        configuracao.load().migrate();
    }

    private <T> T executar(String schema, Supplier<T> operacao) {
        var transacao = new TransactionTemplate(transacoes);
        transacao.setTimeout(25);
        return transacao.execute(status -> {
            jdbc.execute("set local search_path to " + schema);
            jdbc.execute("set local statement_timeout = '15s'");
            return operacao.get();
        });
    }

    private int pid() {
        return jdbc.queryForObject("select pg_backend_pid()", Integer.class);
    }

    private void aguardarBloqueio(int bloqueadora, int bloqueada) throws InterruptedException {
        aguardarBloqueio(bloqueadora, bloqueada, "produto_estoque");
    }

    private void aguardarBloqueio(int bloqueadora, int bloqueada, String tabela) throws InterruptedException {
        long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < limite) {
            if (Boolean.TRUE.equals(jdbc.queryForObject("""
                    select ? = any(pg_blocking_pids(pid)) and query ilike ?
                    from pg_stat_activity where pid = ?
                    """, Boolean.class, bloqueadora, "%" + tabela + "%", bloqueada))) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(25);
        }
        throw new AssertionError("A operação concorrente não aguardou bloqueio em " + tabela + ".");
    }

    private <T> CompletableFuture<T> disputarComCommit(String schema, Runnable primeira, Supplier<T> segunda, String tabela) {
        var pidPrimeira = new CompletableFuture<Integer>();
        var pidSegunda = new CompletableFuture<Integer>();
        var liberarCommit = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            try {
                var titular = executor.submit(() -> executar(schema, () -> {
                    primeira.run();
                    pidPrimeira.complete(pid());
                    aguardar(liberarCommit);
                    return null;
                }));
                int bloqueadora = pidPrimeira.get(10, TimeUnit.SECONDS);
                var concorrente = CompletableFuture.supplyAsync(() -> executar(schema, () -> {
                    pidSegunda.complete(pid());
                    return segunda.get();
                }), executor);
                aguardarBloqueio(bloqueadora, pidSegunda.get(5, TimeUnit.SECONDS), tabela);
                liberarCommit.countDown();
                titular.get(10, TimeUnit.SECONDS);
                concorrente.handle((resultado, falha) -> null).get(10, TimeUnit.SECONDS);
                return concorrente;
            } finally {
                liberarCommit.countDown();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        } catch (ExecutionException | TimeoutException e) {
            throw new AssertionError(e);
        }
    }

    private void aguardar(CountDownLatch liberar) {
        try {
            assertThat(liberar.await(12, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private record Cenario(UUID empresa, UUID usuario, UUID exercicio, UUID periodo, UUID produto,
                           Map<String, UUID> contas) {
    }
}
