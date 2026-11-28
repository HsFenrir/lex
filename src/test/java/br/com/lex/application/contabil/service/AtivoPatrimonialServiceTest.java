package br.com.lex.application.contabil.service;

import br.com.lex.application.contabil.dto.AberturaContabil;
import br.com.lex.application.contabil.dto.AberturaContabil.SaldoPorConta;
import br.com.lex.application.contabil.dto.AquisicaoAtivo;
import br.com.lex.application.contabil.dto.ApropriacaoDepreciacao;
import br.com.lex.application.contabil.dto.ConciliacaoOperacional.Diagnostico;
import br.com.lex.application.contabil.dto.ConciliacaoOperacional.Item;
import br.com.lex.application.contabil.dto.ConciliacaoOperacional.Modulo;
import br.com.lex.application.contabil.dto.Dre.Rubrica;
import br.com.lex.application.contabil.dto.FichaAtivo;
import br.com.lex.application.contabil.dto.ProcessamentoDepreciacao;
import br.com.lex.application.contabil.dto.SaldoInicialPatrimonial;
import br.com.lex.domain.contabil.entity.Empresa;
import br.com.lex.domain.contabil.entity.ExercicioContabil;
import br.com.lex.domain.contabil.entity.PeriodoContabil;
import br.com.lex.domain.contabil.entity.Usuario;
import br.com.lex.domain.contabil.enums.OrigemLancamento;
import br.com.lex.domain.contabil.enums.StatusLancamento;
import br.com.lex.domain.contabil.enums.TipoPartida;
import br.com.lex.domain.contabil.repository.ContaContabilRepository;
import jakarta.persistence.EntityManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static java.util.stream.Collectors.toMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class AtivoPatrimonialServiceTest {

    private static final LocalDate DATA = LocalDate.of(2026, 5, 15);
    private static final String CAIXA = "1.1.01.001";
    private static final String FORNECEDORES = "2.1.01.001";
    private static final String TERRENOS = "1.2.03.001";
    private static final String MAQUINAS = "1.2.03.003";
    private static final String REDUTORA = "1.2.03.102";
    private static final String DESPESA = "6.2.006";

    @Autowired private AtivoPatrimonialService ativos;
    @Autowired private DepreciacaoService depreciacao;
    @Autowired private PlanoContasService planos;
    @Autowired private LancamentoContabilService lancamentos;
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
    void adquireAVistaEPrazoEReconciliaOsDemonstrativos() {
        comSchema(schema -> {
            migrar(schema, null);
            Cenario c = executar(schema, this::criarCenario);
            FichaAtivo maquina = executar(schema, () -> ativos.registrarAquisicao(maquina(c, " maq-1 ", CAIXA)));
            FichaAtivo terreno = executar(schema, () -> ativos.registrarAquisicao(aquisicao(c, "TER-1",
                    c.conta(TERRENOS), c.conta(FORNECEDORES), DATA, null, "2500", false, "2500", null, null)));
            assertThat(maquina.codigo()).isEqualTo("MAQ-1");
            assertThat(maquina.numeroContabil()).isEqualTo(1);
            assertThat(maquina.contaRedutoraId()).isEqualTo(c.conta(REDUTORA));
            assertThat(maquina.contaDespesaId()).isEqualTo(c.conta(DESPESA));
            assertThat(maquina.baseDepreciavel()).isEqualByComparingTo("1000.00");
            assertThat(maquina.situacao()).isEqualTo(FichaAtivo.Situacao.DISPONIBILIDADE_REGISTRADA);
            assertThat(terreno.numeroContabil()).isEqualTo(2);
            assertThat(terreno.depreciavel()).isFalse();
            assertThat(terreno.vidaUtilMeses()).isNull();
            assertThat(terreno.contaRedutoraId()).isNull();
            assertThat(terreno.baseDepreciavel()).isEqualByComparingTo("0");
            assertThat(terreno.situacao()).isEqualTo(FichaAtivo.Situacao.AGUARDANDO_DISPONIBILIDADE);
            executar(schema, () -> {
                assertThat(ativos.listar(c.empresa(), c.usuario())).containsExactly(maquina, terreno);
                assertThat(ativos.consultar(c.empresa(), c.usuario(), maquina.id())).isEqualTo(maquina);
                conferirEscrituracao(c, maquina, CAIXA);
                conferirEscrituracao(c, terreno, FORNECEDORES);
                return null;
            });
            assertThatThrownBy(() -> executar(schema, () -> lancamentos.criarRascunhoEstorno(c.empresa(), c.usuario(),
                    maquina.lancamentoId(), c.exercicio(), c.periodo(), DATA, "Estorno")))
                    .hasMessageContaining("módulo de origem");
        });
    }

    private void conferirEscrituracao(Cenario c, FichaAtivo ativo, String contrapartida) {
        var lancamento = diario.emitir(c.empresa(), c.usuario(), c.exercicio(), DATA, DATA).lancamentos().stream()
                .filter(l -> l.referencia().id().equals(ativo.lancamentoId())).findFirst().orElseThrow();
        assertThat(lancamento.referencia().numero()).isEqualTo(ativo.numeroContabil());
        assertThat(lancamento.origem()).isEqualTo(OrigemLancamento.ATIVO);
        assertThat(lancamento.status()).isEqualTo(StatusLancamento.CONFIRMADO);
        assertThat(lancamento.partidas()).map(p -> new SaldoPorConta(p.contaId(), p.tipo(), p.valor()))
                .containsExactlyInAnyOrder(new SaldoPorConta(ativo.contaPatrimonialId(), TipoPartida.DEBITO, ativo.custoAquisicao()),
                        new SaldoPorConta(c.conta(contrapartida), TipoPartida.CREDITO, ativo.custoAquisicao()));
        assertThat(razao.emitir(c.empresa(), c.usuario(), c.exercicio(), ativo.contaPatrimonialId(), DATA, DATA)
                .saldos().saldoFinal().valor()).isEqualByComparingTo(ativo.custoAquisicao());
        assertThat(balancete.emitir(c.empresa(), c.usuario(), c.exercicio(), DATA, DATA).linhas())
                .filteredOn(l -> l.conta().id().equals(ativo.contaPatrimonialId())).singleElement()
                .satisfies(l -> assertThat(l.saldos().saldoFinal().valor()).isEqualByComparingTo(ativo.custoAquisicao()));
        var bp = balanco.emitir(c.empresa(), c.usuario(), c.exercicio(), DATA);
        assertThat(bp.equilibrado()).isTrue();
        assertThat(bp.secoes().stream().flatMap(s -> s.linhas().stream()))
                .filteredOn(l -> l.conta().id().equals(ativo.contaPatrimonialId())).singleElement()
                .satisfies(l -> assertThat(l.valorApresentado()).isEqualByComparingTo(ativo.custoAquisicao()));
        assertThat(jdbc.queryForObject("""
                select count(*) from auditoria_evento where entidade = 'ativo_patrimonial' and acao = 'AQUISICAO'
                  and empresa_id = ? and usuario_id = ? and entidade_id = ?
                  and dados_novos->>'lancamentoId' = ? and dados_novos->>'numeroContabil' = ?
                  and (dados_novos->>'custoAquisicao')::numeric = ?
                """, Long.class, c.empresa(), c.usuario(), ativo.id(), ativo.lancamentoId().toString(),
                Long.toString(ativo.numeroContabil()), ativo.custoAquisicao())).isEqualTo(1L);
    }

    @Test
    void validaValoresVidaUtilDisponibilidadeEClassificacao() {
        comSchema(schema -> {
            migrar(schema, null);
            Cenario c = executar(schema, this::criarCenario);
            UUID bem = c.conta(MAQUINAS);
            UUID caixa = c.conta(CAIXA);
            UUID despesa = c.conta(DESPESA);
            rejeitar(schema, aquisicao(c, "A", bem, caixa, DATA, DATA, "0", true, "0", 12, despesa), "positivo");
            rejeitar(schema, aquisicao(c, "A", bem, caixa, DATA, DATA, "1.001", true, "0", 12, despesa), "duas casas");
            rejeitar(schema, aquisicao(c, "A", bem, caixa, DATA, DATA, "100", true, "-1", 12, despesa), "residual");
            rejeitar(schema, aquisicao(c, "A", bem, caixa, DATA, DATA, "100", true, "100.01", 12, despesa), "residual");
            rejeitar(schema, aquisicao(c, "A", bem, caixa, DATA, DATA, "100", true, "0", 0, despesa), "vida útil");
            rejeitar(schema, aquisicao(c, "A", bem, caixa, DATA, DATA.minusDays(1), "100", true, "0", 12, despesa),
                    "anteceder");
            rejeitar(schema, aquisicao(c, "A", c.conta(REDUTORA), caixa, DATA, DATA, "100", true, "0", 12, despesa),
                    "Imobilizado");
            rejeitar(schema, aquisicao(c, "A", c.conta(TERRENOS), caixa, DATA, DATA, "100", true, "0", 12, despesa),
                    "redutora");
            rejeitar(schema, aquisicao(c, "A", bem, caixa, DATA, DATA, "100", true, "0", 12, caixa), "despesa");
            rejeitar(schema, aquisicao(c, "A", bem, despesa, DATA, DATA, "100", true, "0", 12, despesa), "contrapartida");
            rejeitar(schema, aquisicao(c, "A", c.conta(TERRENOS), caixa, DATA, DATA, "100", false, "0", 12, null), "Vida útil");
            executar(schema, () -> jdbc.update("update conta_contabil set ativa = false where id = ?", c.conta(REDUTORA)));
            rejeitar(schema, maquina(c, "A", CAIXA), "analíticas e ativas");
            executar(schema, () -> jdbc.update("update conta_contabil set ativa = true where id = ?", c.conta(REDUTORA)));
            FichaAtivo pendente = executar(schema, () -> ativos.registrarAquisicao(
                    aquisicao(c, "A", bem, caixa, DATA, null, "100", true, "100", 12, despesa)));
            assertThat(pendente.baseDepreciavel()).isEqualByComparingTo("0");
            assertThat(pendente.dataDisponibilidade()).isNull();
            assertThat(pendente.situacao()).isEqualTo(FichaAtivo.Situacao.AGUARDANDO_DISPONIBILIDADE);
            assertThat(pendente.numeroContabil()).isEqualTo(1);
        });
    }

    @Test
    void isolaEmpresasEReverteCadastroEscrituracaoSequenciaEAuditoria() {
        comSchema(schema -> {
            migrar(schema, null);
            Cenario c = executar(schema, this::criarCenario);
            Cenario outra = executar(schema, this::criarCenario);
            FichaAtivo primeiro = executar(schema, () -> ativos.registrarAquisicao(maquina(c, "A", CAIXA)));
            executar(schema, () -> ativos.registrarAquisicao(maquina(outra, "A", CAIXA)));
            assertThatThrownBy(() -> executar(schema, () -> ativos.registrarAquisicao(maquina(c, " a ", CAIXA))))
                    .satisfies(erro -> conferirViolacaoPostgres(erro, "23505", "uq_ativo_patrimonial_codigo"));
            assertThatThrownBy(() -> executar(schema, () -> ativos.consultar(outra.empresa(), outra.usuario(), primeiro.id())))
                    .hasMessageContaining("não encontrado");
            assertThatThrownBy(() -> executar(schema, () -> ativos.listar(c.empresa(), outra.usuario())))
                    .hasMessageContaining("não pertence");
            rejeitar(schema, aquisicao(c, "B", outra.conta(MAQUINAS), c.conta(CAIXA), DATA, DATA,
                    "100", true, "0", 12, c.conta(DESPESA)), "Conta patrimonial");
            rejeitar(schema, aquisicao(c, "B", c.conta(MAQUINAS), outra.conta(CAIXA), DATA, DATA,
                    "100", true, "0", 12, c.conta(DESPESA)), "contrapartida");
            assertThatThrownBy(() -> executar(schema, () -> {
                ativos.registrarAquisicao(maquina(c, "B", CAIXA));
                throw new IllegalStateException("Falha após confirmar e auditar.");
            })).hasMessageContaining("Falha após confirmar");
            executar(schema, () -> {
                assertThat(ativos.listar(c.empresa(), c.usuario())).containsExactly(primeiro);
                assertThat(jdbc.queryForObject("select count(*) from ativo_patrimonial", Long.class)).isEqualTo(2L);
                assertThat(jdbc.queryForObject("select count(*) from lancamento_contabil", Long.class)).isEqualTo(2L);
                assertThat(jdbc.queryForObject("select count(*) from partida_contabil", Long.class)).isEqualTo(4L);
                assertThat(jdbc.queryForObject("select count(*) from auditoria_evento where entidade = 'ativo_patrimonial'",
                        Long.class)).isEqualTo(2L);
                assertThat(jdbc.queryForObject("select ultimo_numero from sequencia_lancamento where empresa_id = ?",
                        Long.class, c.empresa())).isEqualTo(1L);
                return null;
            });
        });
    }

    @Test
    void respeitaAberturaECalendarioETransportaSaldoSemDuplicarAtivo() {
        comSchema(schema -> {
            migrar(schema, null);
            Cenario c = executar(schema, this::criarCenario);
            executar(schema, () -> lancamentos.registrarSaldosIniciais(new AberturaContabil(
                    c.empresa(), c.usuario(), c.exercicio(), c.periodo(), DATA, "Migração",
                    List.of(new SaldoPorConta(c.conta(CAIXA), TipoPartida.DEBITO, BigDecimal.ONE),
                            new SaldoPorConta(c.conta("3.1.001"), TipoPartida.CREDITO, BigDecimal.ONE)))));
            rejeitar(schema, aquisicao(c, "A", c.conta(TERRENOS), c.conta(CAIXA), DATA.minusDays(1), null,
                    "100", false, "0", null, null), "anterior à abertura");
            FichaAtivo adquirido = executar(schema, () -> ativos.registrarAquisicao(aquisicao(c, "A",
                    c.conta(MAQUINAS), c.conta(FORNECEDORES), DATA, null, "1200", true, "200", 24, c.conta(DESPESA))));
            assertThat(adquirido.numeroContabil()).isEqualTo(2);
            executar(schema, () -> fechamento.encerrarPeriodo(c.empresa(), c.usuario(), c.exercicio(), c.periodo()));
            rejeitar(schema, maquina(c, "B", CAIXA), "período deve estar aberto");
            LocalDate dezembro = LocalDate.of(2026, 12, 31);
            LocalDate janeiro = dezembro.plusDays(1);
            Cenario seguinte = executar(schema, () -> {
                em.persist(new PeriodoContabil(em.getReference(ExercicioContabil.class, c.exercicio()),
                        (short) 12, dezembro.withDayOfMonth(1), dezembro));
                ExercicioContabil proximo = new ExercicioContabil(em.getReference(Empresa.class, c.empresa()),
                        (short) 2027, janeiro, janeiro.plusYears(1).minusDays(1));
                em.persist(proximo);
                PeriodoContabil periodo = new PeriodoContabil(proximo, (short) 1, janeiro, janeiro.plusDays(30));
                em.persist(periodo);
                return new Cenario(c.empresa(), c.usuario(), proximo.getId(), periodo.getId(), c.plano(), c.contas());
            });
            rejeitar(schema, aquisicao(seguinte, "B", c.conta(TERRENOS), c.conta(CAIXA), janeiro, null,
                    "100", false, "0", null, null), "continuidade do exercício anterior");
            executar(schema, () -> {
                CalendarioTestFixture.preencherEncerradosAteNovembro(jdbc, c.exercicio(), 6);
                return null;
            });
            var encerramento = executar(schema, () -> fechamento.encerrarExercicio(c.empresa(), c.usuario(), c.exercicio()));
            assertThat(encerramento.lancamentoAberturaId()).isNotNull();
            executar(schema, () -> {
                assertThat(ativos.listar(c.empresa(), c.usuario())).containsExactly(adquirido);
                var movimentos = razao.emitir(c.empresa(), c.usuario(), encerramento.exercicioDestinoId(),
                        c.conta(MAQUINAS), janeiro, janeiro);
                assertThat(movimentos.movimentos()).singleElement()
                        .satisfies(m -> assertThat(m.origem()).isEqualTo(OrigemLancamento.ABERTURA));
                assertThat(movimentos.saldos().saldoFinal().valor()).isEqualByComparingTo(adquirido.custoAquisicao());
                return null;
            });
        });
    }

    @Test
    void impedeDuasAquisicoesConcorrentesDoMesmoCodigo() throws Exception {
        String schema = novoSchema();
        try {
            migrar(schema, null);
            Cenario c = executar(schema, this::criarCenario);
            var executor = Executors.newFixedThreadPool(2);
            var pronta = new CompletableFuture<Integer>();
            var tentando = new CompletableFuture<Integer>();
            var liberar = new CountDownLatch(1);
            try {
                var primeira = executor.submit(() -> executar(schema, () -> {
                    var adquirido = ativos.registrarAquisicao(maquina(c, "A", CAIXA));
                    pronta.complete(pid());
                    aguardar(liberar);
                    return adquirido;
                }));
                int bloqueadora = pronta.get(10, TimeUnit.SECONDS);
                var segunda = executor.submit(() -> executar(schema, () -> {
                    tentando.complete(pid());
                    return ativos.registrarAquisicao(maquina(c, "a", FORNECEDORES));
                }));
                int bloqueada = tentando.get(5, TimeUnit.SECONDS);
                aguardarBloqueioPatrimonial(bloqueadora, bloqueada);
                liberar.countDown();
                FichaAtivo adquirido = primeira.get(10, TimeUnit.SECONDS);
                assertThatThrownBy(() -> segunda.get(10, TimeUnit.SECONDS))
                        .isInstanceOfSatisfying(ExecutionException.class, erro ->
                                conferirViolacaoPostgres(erro.getCause(), "23505", "uq_ativo_patrimonial_codigo"));
                assertThat(executar(schema, () -> ativos.listar(c.empresa(), c.usuario()))).containsExactly(adquirido);
                assertThat(executar(schema, () -> jdbc.queryForObject(
                        "select ultimo_numero from sequencia_lancamento", Long.class))).isEqualTo(1L);
            } finally {
                liberar.countDown();
                executor.shutdownNow();
                assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
            }
        } finally {
            removerSchema(schema);
        }
    }

    @Test
    void instalaDesdeV1EAtualizaV8ComVinculoContabilObrigatorio() {
        comSchema(schema -> {
            migrar(schema, "8");
            Cenario c = executar(schema, this::criarCenario);
            migrar(schema, null);
            assertThatThrownBy(() -> executar(schema, () -> jdbc.update("""
                    insert into ativo_patrimonial (id, empresa_id, plano_contas_id, usuario_id, codigo, descricao,
                        conta_patrimonial_id, data_aquisicao, custo_aquisicao, depreciavel, valor_residual)
                    values (?, ?, ?, ?, 'SEM-LANCAMENTO', 'Terreno', ?, ?, 1, false, 0)
                    """, UUID.randomUUID(), c.empresa(), c.plano(), c.usuario(), c.conta(TERRENOS), DATA)))
                    .satisfies(erro -> conferirViolacaoPostgres(erro, "23514", "ck_ativo_patrimonial_contabilizado"));
            assertThat(executar(schema, () -> ativos.listar(c.empresa(), c.usuario()))).isEmpty();
            assertThat(executar(schema, () -> ativos.registrarAquisicao(maquina(c, "A", CAIXA))).numeroContabil())
                    .isEqualTo(1);
            assertThat(executar(schema, () -> jdbc.queryForObject(
                    "select count(*) from flyway_schema_history where success and version in ('1','2','3','4','5','6','7','8','9')",
                    Long.class)))
                    .isEqualTo(9L);
        });
    }

    @Test
    void registraDisponibilidadeUmaVezComAuditoriaEValidaCalendarioETitularidade() {
        comSchema(schema -> {
            migrar(schema, null);
            Cenario c = executar(schema, this::criarCenario);
            Cenario outra = executar(schema, this::criarCenario);
            var pendente = executar(schema, () -> ativos.registrarAquisicao(aquisicao(c, "PENDENTE",
                    c.conta(MAQUINAS), c.conta(CAIXA), DATA, null, "1200", true, "200", 24, c.conta(DESPESA))));
            assertThatThrownBy(() -> executar(schema, () -> apropriar(c, pendente.id())))
                    .hasMessageContaining("disponibilidade para uso");
            assertThatThrownBy(() -> executar(schema, () -> ativos.registrarDisponibilidade(
                    c.empresa(), outra.usuario(), c.exercicio(), c.periodo(), pendente.id(), DATA)))
                    .hasMessageContaining("não pertence");
            assertThatThrownBy(() -> executar(schema, () -> ativos.registrarDisponibilidade(
                    c.empresa(), c.usuario(), c.exercicio(), c.periodo(), pendente.id(), DATA.minusDays(1))))
                    .hasMessageContaining("anteceder a aquisição");
            executar(schema, () -> fechamento.encerrarPeriodo(c.empresa(), c.usuario(), c.exercicio(), c.periodo()));
            assertThatThrownBy(() -> executar(schema, () -> ativos.registrarDisponibilidade(
                    c.empresa(), c.usuario(), c.exercicio(), c.periodo(), pendente.id(), DATA)))
                    .hasMessageContaining("período deve estar aberto");

            var junho = YearMonth.of(2026, 6);
            Cenario destino = periodo(schema, c, junho);
            LocalDate disponibilidade = junho.atDay(12);
            var disponivel = executar(schema, () -> ativos.registrarDisponibilidade(c.empresa(), c.usuario(),
                    c.exercicio(), destino.periodo(), pendente.id(), disponibilidade));
            assertThat(disponivel.dataDisponibilidade()).isEqualTo(disponibilidade);
            assertThat(disponivel.situacao()).isEqualTo(FichaAtivo.Situacao.DISPONIBILIDADE_REGISTRADA);
            assertThat(disponivel.lancamentoId()).isEqualTo(pendente.lancamentoId());
            assertThat(disponivel.custoAquisicao()).isEqualTo(pendente.custoAquisicao());
            assertThat(disponivel.valorResidual()).isEqualTo(pendente.valorResidual());
            assertThat(disponivel.vidaUtilMeses()).isEqualTo(pendente.vidaUtilMeses());
            assertThat(disponivel.contaPatrimonialId()).isEqualTo(pendente.contaPatrimonialId());
            assertThatThrownBy(() -> executar(schema, () -> ativos.registrarDisponibilidade(c.empresa(), c.usuario(),
                    c.exercicio(), destino.periodo(), pendente.id(), disponibilidade.plusDays(1))))
                    .hasMessageContaining("já foi registrada");
            executar(schema, () -> {
                assertThat(jdbc.queryForObject("select count(*) from lancamento_contabil", Long.class)).isEqualTo(1L);
                assertThat(jdbc.queryForObject("""
                        select count(*) from auditoria_evento
                        where entidade_id = ? and usuario_id = ? and acao = 'REGISTRAR_DISPONIBILIDADE'
                          and dados_anteriores->'dataDisponibilidade' = 'null'::jsonb
                          and dados_anteriores->>'situacao' = 'AGUARDANDO_DISPONIBILIDADE'
                          and dados_novos->>'dataDisponibilidade' = ? and dados_novos->>'situacao' = 'DISPONIBILIDADE_REGISTRADA'
                        """, Long.class, pendente.id(), c.usuario(), disponibilidade.toString())).isEqualTo(1L);
                return null;
            });
            var apropriacao = executar(schema, () -> apropriar(destino, pendente.id()));
            assertThat(apropriacao.competencia()).isEqualTo(junho);
            assertThat(apropriacao.valor()).isEqualByComparingTo("41.67");
            assertThatThrownBy(() -> executar(schema, () -> ativos.registrarDisponibilidade(c.empresa(), c.usuario(),
                    c.exercicio(), destino.periodo(), pendente.id(), disponibilidade.plusDays(1))))
                    .hasMessageContaining("competências de depreciação processadas");
        });
    }

    @Test
    void apropriaLinearmenteRespeitaResidualEReconciliaFichaContasEDre() {
        comSchema(schema -> {
            migrar(schema, null);
            Cenario maio = executar(schema, this::criarCenario);
            Cenario junho = periodo(schema, maio, YearMonth.of(2026, 6));
            Cenario julho = periodo(schema, maio, YearMonth.of(2026, 7));
            Cenario agosto = periodo(schema, maio, YearMonth.of(2026, 8));
            UUID despesaComercial = maio.conta("6.1.005");
            var ativo = executar(schema, () -> ativos.registrarAquisicao(aquisicao(maio, "LINEAR",
                    maio.conta(MAQUINAS), maio.conta(CAIXA), DATA, DATA, "12", true, "2", 3, despesaComercial)));
            assertThatThrownBy(() -> executar(schema, () -> apropriar(junho, ativo.id())))
                    .hasMessageContaining("próxima competência do ativo é 2026-05");
            executar(schema, () -> jdbc.update("update conta_contabil set ativa=false where id=?", despesaComercial));
            assertThatThrownBy(() -> executar(schema, () -> apropriar(maio, ativo.id())))
                    .hasMessageContaining("analíticas e ativas");
            executar(schema, () -> jdbc.update("update conta_contabil set ativa=true where id=?", despesaComercial));
            var primeira = executar(schema, () -> apropriar(maio, ativo.id()));
            var segunda = executar(schema, () -> apropriar(junho, ativo.id()));
            var terceira = executar(schema, () -> apropriar(julho, ativo.id()));
            assertThat(primeira.valor()).isEqualByComparingTo("3.33");
            assertThat(segunda.valor()).isEqualByComparingTo("3.34");
            assertThat(terceira.valor()).isEqualByComparingTo("3.33");
            assertThat(primeira.numeroContabil()).isEqualTo(2L);
            assertThat(terceira.numeroContabil()).isEqualTo(4L);
            assertThatThrownBy(() -> executar(schema, () -> apropriar(agosto, ativo.id())))
                    .hasMessageContaining("base depreciável ou vida útil remanescente");
            assertThatThrownBy(() -> executar(schema, () -> lancamentos.criarRascunhoEstorno(maio.empresa(), maio.usuario(),
                    primeira.lancamentoId(), maio.exercicio(), maio.periodo(), DATA.withDayOfMonth(31), "Estorno")))
                    .hasMessageContaining("módulo de origem");
            executar(schema, () -> {
                var ficha = ativos.consultar(maio.empresa(), maio.usuario(), ativo.id());
                assertThat(ativos.listar(maio.empresa(), maio.usuario())).containsExactly(ficha);
                assertThat(ficha.depreciacaoAcumulada()).isEqualByComparingTo("10");
                assertThat(ficha.valorContabilLiquido()).isEqualByComparingTo("2");
                assertThat(ficha.baseDepreciavelRemanescente()).isEqualByComparingTo("0");
                assertThat(ficha.vidaUtilConsumidaMeses()).isEqualTo(3);
                assertThat(ficha.vidaUtilRemanescenteMeses()).isZero();
                assertThat(ficha.apropriacoes()).containsExactly(primeira, segunda, terceira);
                assertThat(ficha.competenciasProcessadas()).containsExactly(
                        YearMonth.of(2026, 5), YearMonth.of(2026, 6), YearMonth.of(2026, 7));
                LocalDate fim = LocalDate.of(2026, 7, 31);
                var diarioEmitido = diario.emitir(maio.empresa(), maio.usuario(), maio.exercicio(), DATA, fim);
                assertThat(diarioEmitido.lancamentos()).filteredOn(l -> l.referencia().id().equals(primeira.lancamentoId()))
                        .singleElement().satisfies(l -> {
                            assertThat(l.origem()).isEqualTo(OrigemLancamento.DEPRECIACAO);
                            assertThat(l.status()).isEqualTo(StatusLancamento.CONFIRMADO);
                            assertThat(l.data()).isEqualTo(DATA.withDayOfMonth(31));
                            assertThat(l.partidas()).map(p -> new SaldoPorConta(p.contaId(), p.tipo(), p.valor()))
                                    .containsExactlyInAnyOrder(new SaldoPorConta(despesaComercial, TipoPartida.DEBITO, primeira.valor()),
                                            new SaldoPorConta(maio.conta(REDUTORA), TipoPartida.CREDITO, primeira.valor()));
                        });
                assertThat(razao.emitir(maio.empresa(), maio.usuario(), maio.exercicio(), maio.conta(REDUTORA), DATA, fim)
                        .saldos().saldoFinal().valor()).isEqualByComparingTo(ficha.depreciacaoAcumulada().negate());
                assertThat(balancete.emitir(maio.empresa(), maio.usuario(), maio.exercicio(), DATA, fim).linhas())
                        .filteredOn(l -> l.conta().id().equals(maio.conta(REDUTORA))).singleElement()
                        .satisfies(l -> assertThat(l.saldos().saldoFinal().valor())
                                .isEqualByComparingTo(ficha.depreciacaoAcumulada().negate()));
                assertThat(dre.emitir(maio.empresa(), maio.usuario(), maio.exercicio(), DATA, fim).linhas())
                        .filteredOn(l -> l.rubrica() == Rubrica.DESPESAS_OPERACIONAIS).singleElement()
                        .satisfies(l -> assertThat(l.valorDemonstrado()).isEqualByComparingTo("-10"));
                var bp = balanco.emitir(maio.empresa(), maio.usuario(), maio.exercicio(), fim);
                assertThat(bp.equilibrado()).isTrue();
                assertThat(bp.secoes().stream().flatMap(s -> s.linhas().stream())
                        .filter(l -> List.of(maio.conta(MAQUINAS), maio.conta(REDUTORA)).contains(l.conta().id()))
                        .map(l -> l.valorApresentado()).reduce(BigDecimal.ZERO, BigDecimal::add))
                        .isEqualByComparingTo(ficha.valorContabilLiquido());
                return null;
            });
        });
    }

    @Test
    void fechamentoMensalExigeAteCompetenciaZeroEDispensaAtivosSemBaseOuDisponibilidade() {
        comSchema(schema -> {
            migrar(schema, null);
            Cenario maio = executar(schema, this::criarCenario);
            Cenario junho = periodo(schema, maio, YearMonth.of(2026, 6));
            Cenario julho = periodo(schema, maio, YearMonth.of(2026, 7));
            var ativo = executar(schema, () -> ativos.registrarAquisicao(aquisicao(maio, "CENTAVO",
                    maio.conta(MAQUINAS), maio.conta(CAIXA), DATA, DATA, "0.01", true, "0", 3, maio.conta(DESPESA))));
            executar(schema, () -> ativos.registrarAquisicao(aquisicao(maio, "BASE-ZERO",
                    maio.conta(MAQUINAS), maio.conta(CAIXA), DATA, DATA, "1", true, "1", 3, maio.conta(DESPESA))));
            executar(schema, () -> ativos.registrarAquisicao(aquisicao(maio, "INATIVO-PARA-USO",
                    maio.conta(MAQUINAS), maio.conta(CAIXA), DATA, null, "1", true, "0", 3, maio.conta(DESPESA))));
            executar(schema, () -> ativos.registrarAquisicao(aquisicao(maio, "TERRENO",
                    maio.conta(TERRENOS), maio.conta(CAIXA), DATA, DATA, "1", false, "0", null, null)));
            assertThatThrownBy(() -> executar(schema, () -> fechamento.encerrarPeriodo(
                    maio.empresa(), maio.usuario(), maio.exercicio(), maio.periodo())))
                    .hasMessage("Depreciação pendente por ativo e competência: CENTAVO (2026-05)");
            var zero = executar(schema, () -> apropriar(maio, ativo.id()));
            assertThat(zero.valor()).isEqualByComparingTo("0");
            assertThat(zero.lancamentoId()).isNull();
            assertThat(zero.numeroContabil()).isNull();
            var ficha = executar(schema, () -> ativos.consultar(maio.empresa(), maio.usuario(), ativo.id()));
            assertThat(ficha.vidaUtilConsumidaMeses()).isEqualTo(1);
            assertThat(ficha.vidaUtilRemanescenteMeses()).isEqualTo(2);
            assertThat(ficha.depreciacaoAcumulada()).isEqualByComparingTo("0");
            executar(schema, () -> fechamento.encerrarPeriodo(maio.empresa(), maio.usuario(), maio.exercicio(), maio.periodo()));
            assertThatThrownBy(() -> executar(schema, () -> apropriar(maio, ativo.id())))
                    .hasMessageContaining("período deve estar aberto");
            var positiva = executar(schema, () -> apropriar(junho, ativo.id()));
            assertThat(positiva.valor()).isEqualByComparingTo("0.01");
            assertThat(positiva.numeroContabil()).isEqualTo(5L);
            executar(schema, () -> fechamento.encerrarPeriodo(junho.empresa(), junho.usuario(), junho.exercicio(), junho.periodo()));
            executar(schema, () -> fechamento.encerrarPeriodo(julho.empresa(), julho.usuario(), julho.exercicio(), julho.periodo()));
        });
    }

    @Test
    void fechamentoAnualExigeCompetenciaDeDezembroETransportaDepreciacao() {
        comSchema(schema -> {
            migrar(schema, null);
            Cenario c = executar(schema, this::criarCenario);
            YearMonth dezembro = YearMonth.of(2026, 12);
            Cenario destino = periodo(schema, c, dezembro);
            var ativo = executar(schema, () -> ativos.registrarAquisicao(aquisicao(c, "ANUAL",
                    c.conta(MAQUINAS), c.conta(CAIXA), DATA, dezembro.atDay(20), "12", true, "0", 2, c.conta(DESPESA))));
            executar(schema, () -> fechamento.encerrarPeriodo(c.empresa(), c.usuario(), c.exercicio(), c.periodo()));
            executar(schema, () -> {
                CalendarioTestFixture.preencherEncerradosAteNovembro(jdbc, c.exercicio(), 6);
                return null;
            });
            assertThatThrownBy(() -> executar(schema, () -> fechamento.encerrarExercicio(c.empresa(), c.usuario(), c.exercicio())))
                    .hasMessageContaining("ANUAL (2026-12)");
            var primeira = executar(schema, () -> apropriar(destino, ativo.id()));
            var encerrado = executar(schema, () -> fechamento.encerrarExercicio(c.empresa(), c.usuario(), c.exercicio()));
            executar(schema, () -> {
                var dreEncerrada = dre.emitir(c.empresa(), c.usuario(), c.exercicio(), dezembro.atDay(1), dezembro.atEndOfMonth());
                assertThat(dreEncerrada.linhas()).filteredOn(l -> l.rubrica() == Rubrica.DESPESAS_OPERACIONAIS)
                        .singleElement().satisfies(l -> assertThat(l.valorDemonstrado()).isEqualByComparingTo("-6"));
                assertThat(razao.emitir(c.empresa(), c.usuario(), c.exercicio(), c.conta(DESPESA),
                        dezembro.atDay(1), dezembro.atEndOfMonth()).saldos().saldoFinal().valor()).isZero();
                return null;
            });
            var segunda = executar(schema, () -> {
                UUID janeiro = jdbc.queryForObject("select id from periodo_contabil where exercicio_id=? and mes=1",
                        UUID.class, encerrado.exercicioDestinoId());
                return depreciacao.apropriar(c.empresa(), c.usuario(), encerrado.exercicioDestinoId(), janeiro, ativo.id());
            });
            assertThat(segunda.valor()).isEqualByComparingTo("6");
            assertThat(segunda.numeroContabil()).isEqualTo(2L);
            var posicao = conciliarConta(schema, c, encerrado.exercicioDestinoId(), LocalDate.of(2027, 1, 31), REDUTORA);
            assertThat(posicao.diagnostico()).isEqualTo(Diagnostico.CONCILIADO);
            assertThat(posicao.saldoOperacional()).isEqualByComparingTo("12.00");
            executar(schema, () -> {
                var ficha = ativos.consultar(c.empresa(), c.usuario(), ativo.id());
                assertThat(ficha.apropriacoes()).containsExactly(primeira, segunda);
                assertThat(ficha.valorContabilLiquido()).isEqualByComparingTo("0");
                var saldo = razao.emitir(c.empresa(), c.usuario(), encerrado.exercicioDestinoId(), c.conta(REDUTORA),
                        LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 31));
                assertThat(saldo.saldos().saldoFinal().valor()).isEqualByComparingTo("-12");
                return null;
            });
        });
    }

    @Test
    void serializaApropriacoesConcorrentesDoMesmoAtivo() throws Exception {
        String schema = novoSchema();
        try {
            migrar(schema, null);
            Cenario c = executar(schema, this::criarCenario);
            var ativo = executar(schema, () -> ativos.registrarAquisicao(maquina(c, "CONCORRENTE", CAIXA)));
            var executor = Executors.newFixedThreadPool(2);
            var pronta = new CompletableFuture<Integer>();
            var tentando = new CompletableFuture<Integer>();
            var liberar = new CountDownLatch(1);
            try {
                var primeira = executor.submit(() -> executar(schema, () -> {
                    var apropriacao = apropriar(c, ativo.id());
                    pronta.complete(pid());
                    aguardar(liberar);
                    return apropriacao;
                }));
                int bloqueadora = pronta.get(10, TimeUnit.SECONDS);
                var segunda = executor.submit(() -> executar(schema, () -> {
                    tentando.complete(pid());
                    return apropriar(c, ativo.id());
                }));
                aguardarBloqueioPatrimonial(bloqueadora, tentando.get(5, TimeUnit.SECONDS));
                liberar.countDown();
                var confirmada = primeira.get(10, TimeUnit.SECONDS);
                assertThatThrownBy(() -> segunda.get(10, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                        .hasRootCauseMessage("A competência 2026-05 já foi processada para o ativo.");
                executar(schema, () -> {
                    var ficha = ativos.consultar(c.empresa(), c.usuario(), ativo.id());
                    assertThat(ficha.apropriacoes()).containsExactly(confirmada);
                    assertThat(jdbc.queryForObject("select ultimo_numero from sequencia_lancamento", Long.class)).isEqualTo(2L);
                    return null;
                });
            } finally {
                liberar.countDown();
                executor.shutdownNow();
                assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
            }
        } finally {
            removerSchema(schema);
        }
    }

    private void aguardarBloqueioPatrimonial(int bloqueadora, int bloqueada) throws InterruptedException {
        aguardarBloqueio(bloqueadora, bloqueada, "ativo_patrimonial");
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
        throw new AssertionError("A segunda operação não aguardou o bloqueio em " + tabela + ".");
    }

    @Test
    void reverteApropriacaoComAuditoriaEValidaUnicidadeDaCompetenciaNaV10() {
        comSchema(schema -> {
            migrar(schema, "9");
            Cenario c = executar(schema, this::criarCenario);
            UUID ativoId = executar(schema, () -> semearAquisicaoLegada(c, maquina(c, "A", CAIXA)));
            migrar(schema, null);
            assertThatThrownBy(() -> executar(schema, () -> {
                apropriar(c, ativoId);
                throw new IllegalStateException("Falha após apropriação.");
            })).hasMessageContaining("Falha após apropriação");
            executar(schema, () -> {
                var ativo = ativos.consultar(c.empresa(), c.usuario(), ativoId);
                assertThat(ativo.origem()).isEqualTo(FichaAtivo.Origem.AQUISICAO);
                assertThat(ativo.custoAquisicao()).isEqualByComparingTo("1200");
                assertThat(ativo.apropriacoes()).isEmpty();
                assertThat(jdbc.queryForObject("select count(*) from lancamento_contabil", Long.class)).isEqualTo(1L);
                assertThat(jdbc.queryForObject("select count(*) from partida_contabil", Long.class)).isEqualTo(2L);
                assertThat(jdbc.queryForObject("select count(*) from auditoria_evento where entidade = 'apropriacao_depreciacao'",
                        Long.class)).isZero();
                return null;
            });
            var apropriacao = executar(schema, () -> apropriar(c, ativoId));
            assertThat(apropriacao.numeroContabil()).isEqualTo(2L);
            assertThatThrownBy(() -> executar(schema, () -> apropriar(c, ativoId)))
                    .hasMessageContaining("já foi processada");
            assertThatThrownBy(() -> executar(schema, () -> jdbc.update("""
                    insert into apropriacao_depreciacao (id, empresa_id, ativo_id, exercicio_id, periodo_id,
                        usuario_id, competencia, valor) values (?, ?, ?, ?, ?, ?, ?, 0)
                    """, UUID.randomUUID(), c.empresa(), ativoId, c.exercicio(), c.periodo(), c.usuario(),
                    DATA.withDayOfMonth(1))))
                    .satisfies(erro -> conferirViolacaoPostgres(erro, "23505", "uq_depreciacao_ativo_competencia"));
            assertThat(executar(schema, () -> jdbc.queryForObject("""
                    select count(*) from auditoria_evento where entidade_id = ? and usuario_id = ?
                      and acao = 'APROPRIAR_DEPRECIACAO' and dados_novos->>'lancamentoId' = ?
                    """, Long.class, apropriacao.id(), c.usuario(), apropriacao.lancamentoId().toString()))).isEqualTo(1L);
        });
    }

    @Test
    void loteProcessaElegiveisSeparaIgnoradosERepeticaoPreservaNumeracao() {
        comSchema(schema -> {
            migrar(schema, null);
            Cenario c = executar(schema, this::criarCenario);
            var a = executar(schema, () -> ativos.registrarAquisicao(maquina(c, "A", CAIXA)));
            var b = executar(schema, () -> ativos.registrarAquisicao(maquina(c, "B", FORNECEDORES)));
            var antigo = executar(schema, () -> ativos.registrarAquisicao(maquina(c, "JA", CAIXA)));
            var zero = executar(schema, () -> ativos.registrarAquisicao(aquisicao(c, "ZERO", c.conta(MAQUINAS),
                    c.conta(CAIXA), DATA, DATA, "0.01", true, "0", 3, c.conta(DESPESA))));
            var indisponivel = executar(schema, () -> ativos.registrarAquisicao(aquisicao(c, "INDISPONIVEL",
                    c.conta(MAQUINAS), c.conta(CAIXA), DATA, null, "100", true, "0", 3, c.conta(DESPESA))));
            var terreno = executar(schema, () -> ativos.registrarAquisicao(aquisicao(c, "TERRENO", c.conta(TERRENOS),
                    c.conta(CAIXA), DATA, DATA, "100", false, "0", null, null)));
            var anterior = executar(schema, () -> apropriar(c, antigo.id()));
            var lote = executar(schema, () -> processar(c));
            assertThat(lote.competencia()).isEqualTo(YearMonth.of(2026, 5));
            assertThat(lote.processados()).extracting(ApropriacaoDepreciacao::ativoId)
                    .containsExactlyInAnyOrder(a.id(), b.id(), zero.id());
            assertThat(lote.naoElegiveis()).extracting(ProcessamentoDepreciacao.AtivoNaoElegivel::ativoId)
                    .containsExactlyInAnyOrder(indisponivel.id(), terreno.id());
            assertThat(lote.jaProcessados()).containsExactly(anterior);
            assertThat(lote.valorTotal()).isEqualByComparingTo("83.34");
            var repeticao = executar(schema, () -> processar(c));
            assertThat(repeticao.processados()).isEmpty();
            assertThat(repeticao.valorTotal()).isZero();
            assertThat(repeticao.jaProcessados()).contains(anterior).containsAll(lote.processados()).hasSize(4);
            executar(schema, () -> {
                var livro = diario.emitir(c.empresa(), c.usuario(), c.exercicio(), DATA, DATA.withDayOfMonth(31));
                for (var linha : lote.processados()) {
                    assertThat(ativos.consultar(c.empresa(), c.usuario(), linha.ativoId()).apropriacoes()).containsExactly(linha);
                    if (linha.ativoId().equals(zero.id())) {
                        assertThat(linha.valor()).isZero();
                        assertThat(linha.lancamentoId()).isNull();
                        assertThat(linha.numeroContabil()).isNull();
                        assertThat(jdbc.queryForObject("""
                                select count(*) from auditoria_evento
                                where entidade_id = ? and usuario_id = ? and acao = 'APROPRIAR_DEPRECIACAO'
                                  and dados_novos->'lancamentoId' = 'null'::jsonb
                                  and (dados_novos->>'valor')::numeric = 0
                                """, Long.class, linha.id(), c.usuario())).isEqualTo(1L);
                    } else {
                        assertThat(livro.lancamentos()).filteredOn(l -> l.referencia().id().equals(linha.lancamentoId()))
                                .singleElement().satisfies(l -> {
                                    assertThat(l.origem()).isEqualTo(OrigemLancamento.DEPRECIACAO);
                                    assertThat(l.partidas()).map(p -> new SaldoPorConta(p.contaId(), p.tipo(), p.valor()))
                                            .containsExactlyInAnyOrder(
                                                    new SaldoPorConta(c.conta(DESPESA), TipoPartida.DEBITO, linha.valor()),
                                                    new SaldoPorConta(c.conta(REDUTORA), TipoPartida.CREDITO, linha.valor()));
                                });
                    }
                }
                assertThat(jdbc.queryForObject("select ultimo_numero from sequencia_lancamento", Long.class)).isEqualTo(9L);
                assertThat(jdbc.queryForObject("select count(*) from auditoria_evento where entidade = 'apropriacao_depreciacao'",
                        Long.class)).isEqualTo(4L);
                return null;
            });
        });
    }

    @Test
    void loteDiagnosticaCompetenciaAnteriorSemEscriturarAtivoApto() {
        comSchema(schema -> {
            migrar(schema, null);
            Cenario c = executar(schema, this::criarCenario);
            var mes = YearMonth.of(2026, 6);
            Cenario junho = periodo(schema, c, mes);
            var atrasado = executar(schema, () -> ativos.registrarAquisicao(maquina(c, "ATRASADO", CAIXA)));
            var apto = executar(schema, () -> ativos.registrarAquisicao(aquisicao(c, "APTO", c.conta(MAQUINAS),
                    c.conta(CAIXA), DATA, mes.atDay(1), "1200", true, "200", 24, c.conta(DESPESA))));
            assertThatThrownBy(() -> executar(schema, () -> processar(junho)))
                    .hasMessageContaining("ATRASADO (2026-05)");
            assertThat(executar(schema, () -> ativos.consultar(c.empresa(), c.usuario(), apto.id())).apropriacoes()).isEmpty();
            assertThat(executar(schema, () -> jdbc.queryForObject("select ultimo_numero from sequencia_lancamento", Long.class)))
                    .isEqualTo(2L);
            var maio = executar(schema, () -> processar(c));
            assertThat(maio.processados()).extracting(ApropriacaoDepreciacao::ativoId).containsExactly(atrasado.id());
            assertThat(maio.naoElegiveis()).singleElement().satisfies(item -> {
                assertThat(item.ativoId()).isEqualTo(apto.id());
                assertThat(item.motivo()).contains("2026-06");
            });
            assertThat(executar(schema, () -> processar(junho)).processados()).extracting(ApropriacaoDepreciacao::ativoId)
                    .containsExactlyInAnyOrder(atrasado.id(), apto.id());
        });
    }

    @Test
    void loteReverteTodosOsAtivosEASequenciaQuandoOUltimoFalha() {
        comSchema(schema -> {
            migrar(schema, null);
            Cenario c = executar(schema, this::criarCenario);
            executar(schema, () -> ativos.registrarAquisicao(maquina(c, "A", CAIXA)));
            executar(schema, () -> ativos.registrarAquisicao(maquina(c, "B", CAIXA)));
            executar(schema, () -> {
                UUID ultimo = jdbc.queryForObject("select id from ativo_patrimonial order by id desc limit 1", UUID.class);
                jdbc.execute("alter table apropriacao_depreciacao add constraint teste_falha_no_ultimo"
                        + " check (ativo_id <> '" + ultimo + "'::uuid)");
                return null;
            });
            assertThatThrownBy(() -> executar(schema, () -> processar(c)))
                    .satisfies(erro -> conferirViolacaoPostgres(erro, "23514", "teste_falha_no_ultimo"));
            executar(schema, () -> {
                assertThat(jdbc.queryForObject("select count(*) from apropriacao_depreciacao", Long.class)).isZero();
                assertThat(jdbc.queryForObject("select count(*) from lancamento_contabil", Long.class)).isEqualTo(2L);
                assertThat(jdbc.queryForObject("select count(*) from partida_contabil", Long.class)).isEqualTo(4L);
                assertThat(jdbc.queryForObject("select count(*) from auditoria_evento where entidade = 'apropriacao_depreciacao'",
                        Long.class)).isZero();
                assertThat(jdbc.queryForObject("select ultimo_numero from sequencia_lancamento", Long.class)).isEqualTo(2L);
                jdbc.execute("alter table apropriacao_depreciacao drop constraint teste_falha_no_ultimo");
                return null;
            });
            assertThat(executar(schema, () -> processar(c)).processados()).extracting(ApropriacaoDepreciacao::numeroContabil)
                    .containsExactly(3L, 4L);
        });
    }

    @Test
    void rejeitaAquisicaoComDisponibilidadeEmCompetenciaEncerrada() {
        comSchema(schema -> {
            migrar(schema, "10");
            Cenario c = executar(schema, this::criarCenario);
            // Simula um mês posterior encerrado antes de a competência da compra ser cadastrada.
            var maio = YearMonth.of(2026, 5);
            executar(schema, () -> jdbc.update("delete from periodo_contabil where id = ?", c.periodo()));
            var junho = YearMonth.of(2026, 6);
            Cenario posterior = periodo(schema, c, junho);
            executar(schema, () -> jdbc.update("update periodo_contabil set status = 'ENCERRADO' where id = ?",
                    posterior.periodo()));
            Cenario compra = periodo(schema, c, maio);
            migrar(schema, null);
            rejeitar(schema, aquisicao(compra, "RETROATIVO", c.conta(MAQUINAS), c.conta(CAIXA),
                    DATA, junho.atDay(15), "100", true, "0", 12, c.conta(DESPESA)), "competência encerrada");
            assertThat(executar(schema, () -> ativos.listar(c.empresa(), c.usuario()))).isEmpty();
            assertThat(executar(schema, () -> jdbc.queryForObject("select count(*) from sequencia_lancamento", Long.class)))
                    .isZero();
        });
    }

    @Test
    void loteAguardaApropriacaoIndividualDoUltimoAtivoEReusaResultado() throws Exception {
        String schema = novoSchema();
        try {
            migrar(schema, null);
            Cenario c = executar(schema, this::criarCenario);
            executar(schema, () -> ativos.registrarAquisicao(maquina(c, "A", CAIXA)));
            executar(schema, () -> ativos.registrarAquisicao(maquina(c, "B", CAIXA)));
            UUID ultimo = executar(schema, () -> jdbc.queryForObject(
                    "select id from ativo_patrimonial order by id desc limit 1", UUID.class));
            var executor = Executors.newFixedThreadPool(2);
            var pronta = new CompletableFuture<Integer>();
            var tentando = new CompletableFuture<Integer>();
            var liberar = new CountDownLatch(1);
            try {
                var individual = executor.submit(() -> executar(schema, () -> {
                    var linha = apropriar(c, ultimo);
                    pronta.complete(pid());
                    aguardar(liberar);
                    return linha;
                }));
                int bloqueadora = pronta.get(10, TimeUnit.SECONDS);
                var coletivo = executor.submit(() -> executar(schema, () -> {
                    tentando.complete(pid());
                    return processar(c);
                }));
                aguardarBloqueioPatrimonial(bloqueadora, tentando.get(5, TimeUnit.SECONDS));
                liberar.countDown();
                var lote = coletivo.get(10, TimeUnit.SECONDS);
                assertThat(lote.jaProcessados()).containsExactly(individual.get(10, TimeUnit.SECONDS));
                assertThat(lote.processados()).singleElement().satisfies(l -> {
                    assertThat(l.ativoId()).isNotEqualTo(ultimo);
                    assertThat(l.numeroContabil()).isEqualTo(4L);
                });
            } finally {
                liberar.countDown();
                executor.shutdownNow();
                assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
            }
        } finally {
            removerSchema(schema);
        }
    }

    @Test
    void fechamentoAguardaCommitDaApropriacaoAntesDeVerificarPendencias() throws Exception {
        String schema = novoSchema();
        try {
            migrar(schema, null);
            Cenario c = executar(schema, this::criarCenario);
            executar(schema, () -> ativos.registrarAquisicao(maquina(c, "A", CAIXA)));
            var executor = Executors.newFixedThreadPool(2);
            var pronta = new CompletableFuture<Integer>();
            var tentando = new CompletableFuture<Integer>();
            var liberar = new CountDownLatch(1);
            try {
                var apropriacao = executor.submit(() -> executar(schema, () -> {
                    var lote = processar(c);
                    pronta.complete(pid());
                    aguardar(liberar);
                    return lote;
                }));
                int bloqueadora = pronta.get(10, TimeUnit.SECONDS);
                var encerramento = executor.submit(() -> executar(schema, () -> {
                    tentando.complete(pid());
                    return fechamento.encerrarPeriodo(c.empresa(), c.usuario(), c.exercicio(), c.periodo());
                }));
                aguardarBloqueio(bloqueadora, tentando.get(5, TimeUnit.SECONDS), "periodo_contabil");
                liberar.countDown();
                assertThat(apropriacao.get(10, TimeUnit.SECONDS).processados()).hasSize(1);
                assertThat(encerramento.get(10, TimeUnit.SECONDS).periodoId()).isEqualTo(c.periodo());
                assertThatThrownBy(() -> executar(schema, () -> processar(c))).hasMessageContaining("período deve estar aberto");
            } finally {
                liberar.countDown();
                executor.shutdownNow();
                assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
            }
        } finally {
            removerSchema(schema);
        }
    }

    private ProcessamentoDepreciacao processar(Cenario c) {
        return depreciacao.processarCompetencia(c.empresa(), c.usuario(), c.exercicio(), c.periodo());
    }

    @Test
    void impedeLacunaNoEncerramentoEMesRetroativoAposOMarcoInicial() {
        comSchema(schema -> {
            migrar(schema, null);
            Cenario maio = executar(schema, this::criarCenario);
            Cenario julho = periodo(schema, maio, YearMonth.of(2026, 7));
            executar(schema, () -> fechamento.encerrarPeriodo(maio.empresa(), maio.usuario(), maio.exercicio(), maio.periodo()));
            assertThatThrownBy(() -> executar(schema, () -> fechamento.encerrarPeriodo(
                    julho.empresa(), julho.usuario(), julho.exercicio(), julho.periodo())))
                    .hasMessageContaining("lacuna na competência de mês 6");
            assertThatThrownBy(() -> executar(schema, () -> jdbc.update("update periodo_contabil set status='ENCERRADO' where id=?",
                    julho.periodo()))).satisfies(erro -> conferirViolacaoPostgres(erro, "23514", "ck_periodo_calendario_continuo"));
            Cenario junho = periodo(schema, maio, YearMonth.of(2026, 6));
            executar(schema, () -> fechamento.encerrarPeriodo(junho.empresa(), junho.usuario(), junho.exercicio(), junho.periodo()));
            executar(schema, () -> fechamento.encerrarPeriodo(julho.empresa(), julho.usuario(), julho.exercicio(), julho.periodo()));
            assertThatThrownBy(() -> periodo(schema, maio, YearMonth.of(2026, 4)))
                    .satisfies(erro -> conferirViolacaoPostgres(erro, "23514", "ck_periodo_calendario_encerrado"));
            assertThat(executar(schema, () -> jdbc.queryForObject("select count(*) from lancamento_contabil", Long.class))).isZero();
        });
    }

    @Test
    void upgradeV11PreservaLegadoMasImpedeNovasObrigacoesInclusiveDeValorZero() {
        comSchema(schema -> {
            migrar(schema, "10");
            Cenario c = executar(schema, this::criarCenario);
            Cenario junho = periodo(schema, c, YearMonth.of(2026, 6));
            var pendente = executar(schema, () -> semearAquisicaoLegada(c, aquisicao(c, "PENDENTE", c.conta(MAQUINAS),
                    c.conta(CAIXA), DATA, null, "0.01", true, "0", 5, c.conta(DESPESA))));
            executar(schema, () -> jdbc.update("update periodo_contabil set status='ENCERRADO' where id=?", junho.periodo()));
            migrar(schema, null);
            // Em 5 parcelas de um centavo, maio e junho arredondam a zero, mas ambas são obrigatórias.
            rejeitar(schema, aquisicao(c, "ZERO", c.conta(MAQUINAS), c.conta(CAIXA), DATA, DATA,
                    "0.01", true, "0", 5, c.conta(DESPESA)), "competência encerrada");
            assertThatThrownBy(() -> executar(schema, () -> ativos.registrarDisponibilidade(
                    c.empresa(), c.usuario(), c.exercicio(), c.periodo(), pendente, DATA)))
                    .hasMessageContaining("competência encerrada");
            executar(schema, () -> {
                assertThat(ativos.consultar(c.empresa(), c.usuario(), pendente).dataDisponibilidade()).isNull();
                assertThat(jdbc.queryForObject("select count(*) from ativo_patrimonial", Long.class)).isEqualTo(1L);
                assertThat(jdbc.queryForObject("select count(*) from apropriacao_depreciacao", Long.class)).isZero();
                assertThat(jdbc.queryForObject("select count(*) from auditoria_evento", Long.class)).isEqualTo(1L);
                assertThat(jdbc.queryForObject("select count(*) from lancamento_contabil", Long.class)).isEqualTo(1L);
                assertThat(jdbc.queryForObject("select ultimo_numero from sequencia_lancamento", Long.class)).isEqualTo(1L);
                assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version='11' and success",
                        Long.class)).isEqualTo(1L);
                return null;
            });
            // A primeira parcela já esgota a base: não há obrigação em junho.
            var valido = executar(schema, () -> ativos.registrarAquisicao(aquisicao(c, "ESGOTA",
                    c.conta(MAQUINAS), c.conta(CAIXA), DATA, DATA, "0.01", true, "0", 2, c.conta(DESPESA))));
            assertThat(executar(schema, () -> apropriar(c, valido.id())).valor()).isEqualByComparingTo("0.01");
        });
    }

    @Test
    void rejeitaObrigacaoEmLacunaLegadaMesmoQuandoPeriodoEncerradoFicaForaDaVidaUtil() {
        comSchema(schema -> {
            migrar(schema, "10");
            Cenario c = executar(schema, this::criarCenario);
            Cenario julho = periodo(schema, c, YearMonth.of(2026, 7));
            executar(schema, () -> jdbc.update("update periodo_contabil set status='ENCERRADO' where id=?", julho.periodo()));
            migrar(schema, null);
            rejeitar(schema, aquisicao(c, "LACUNA", c.conta(MAQUINAS), c.conta(CAIXA), DATA, DATA,
                    "100", true, "0", 2, c.conta(DESPESA)), "lacuna anterior a uma competência encerrada");
            assertThat(executar(schema, () -> ativos.listar(c.empresa(), c.usuario()))).isEmpty();
        });
    }

    @Test
    void rejeitaObrigacaoEmCompetenciaEncerradaDeExercicioPosterior() {
        comSchema(schema -> {
            migrar(schema, "10");
            Cenario c = executar(schema, this::criarCenario);
            executar(schema, () -> {
                var inicio = LocalDate.of(2027, 1, 1);
                var futuro = new ExercicioContabil(em.getReference(Empresa.class, c.empresa()), (short) 2027,
                        inicio, inicio.withMonth(12).withDayOfMonth(31));
                em.persist(futuro);
                var janeiro = new PeriodoContabil(futuro, (short) 1, inicio, inicio.withDayOfMonth(31));
                em.persist(janeiro);
                em.flush();
                return jdbc.update("update periodo_contabil set status = 'ENCERRADO' where id = ?", janeiro.getId());
            });
            migrar(schema, null);
            rejeitar(schema, aquisicao(c, "INTERANUAL", c.conta(MAQUINAS), c.conta(CAIXA), DATA, DATA,
                    "100", true, "0", 9, c.conta(DESPESA)), "competência encerrada");
            assertThat(executar(schema, () -> ativos.listar(c.empresa(), c.usuario()))).isEmpty();
        });
    }

    @Test
    void impedePeriodosEmExercicioEncerradoEExerciciosRetroativos() {
        comSchema(schema -> {
            migrar(schema, null);
            Cenario c = executar(schema, this::criarCenario);
            assertThatThrownBy(() -> executar(schema, () -> jdbc.update("""
                    insert into exercicio_contabil (empresa_id, ano, data_inicio, data_fim)
                    values (?, 2025, date '2025-01-01', date '2025-12-31')
                    """, c.empresa()))).satisfies(erro -> conferirViolacaoPostgres(erro, "23514", "ck_exercicio_ordem_calendario"));
            executar(schema, () -> jdbc.update("update exercicio_contabil set status='ENCERRADO' where id=?", c.exercicio()));
            assertThatThrownBy(() -> periodo(schema, c, YearMonth.of(2026, 6)))
                    .satisfies(erro -> conferirViolacaoPostgres(erro, "23514", "ck_periodo_calendario_encerrado"));
        });
    }

    @Test
    void aberturaDeMigracaoDefineInicioValidoMesmoComMesesAnterioresCadastrados() {
        comSchema(schema -> {
            migrar(schema, null);
            Cenario maio = executar(schema, this::criarCenario);
            Cenario julho = periodo(schema, maio, YearMonth.of(2026, 7));
            LocalDate abertura = LocalDate.of(2026, 7, 15);
            executar(schema, () -> lancamentos.registrarSaldosIniciais(new AberturaContabil(
                    julho.empresa(), julho.usuario(), julho.exercicio(), julho.periodo(), abertura, "Migração",
                    List.of(new SaldoPorConta(julho.conta(CAIXA), TipoPartida.DEBITO, BigDecimal.ONE),
                            new SaldoPorConta(julho.conta("3.1.001"), TipoPartida.CREDITO, BigDecimal.ONE)))));
            var adquirido = executar(schema, () -> ativos.registrarAquisicao(aquisicao(julho, "TERRENO",
                    julho.conta(TERRENOS), julho.conta(CAIXA), abertura, abertura, "1", false, "0", null, null)));
            assertThat(adquirido.numeroContabil()).isEqualTo(2L);
            executar(schema, () -> fechamento.encerrarPeriodo(julho.empresa(), julho.usuario(), julho.exercicio(), julho.periodo()));
            assertThat(executar(schema, () -> jdbc.queryForObject("select status from periodo_contabil where id=?",
                    String.class, maio.periodo()))).isEqualTo("ABERTO");
        });
    }

    @Test
    void fechamentoAguardaDisponibilidadeConcorrenteEDetectaAObrigacao() throws Exception {
        String schema = novoSchema();
        try {
            migrar(schema, null);
            Cenario c = executar(schema, this::criarCenario);
            var ativo = executar(schema, () -> ativos.registrarAquisicao(aquisicao(c, "EM-USO",
                    c.conta(MAQUINAS), c.conta(CAIXA), DATA, null, "0.01", true, "0", 5, c.conta(DESPESA))));
            try (var executor = Executors.newFixedThreadPool(2)) {
                var pronto = new CompletableFuture<Integer>();
                var tentando = new CompletableFuture<Integer>();
                var liberar = new CountDownLatch(1);
                try {
                    var disponibilidade = executor.submit(() -> executar(schema, () -> {
                        var ficha = ativos.registrarDisponibilidade(c.empresa(), c.usuario(), c.exercicio(), c.periodo(),
                                ativo.id(), DATA);
                        pronto.complete(pid());
                        aguardar(liberar);
                        return ficha;
                    }));
                    int processando = pronto.get(10, TimeUnit.SECONDS);
                    var tentativa = executor.submit(() -> executar(schema, () -> {
                        tentando.complete(pid());
                        return fechamento.encerrarPeriodo(c.empresa(), c.usuario(), c.exercicio(), c.periodo());
                    }));
                    aguardarBloqueio(processando, tentando.get(5, TimeUnit.SECONDS), "periodo_contabil");
                    liberar.countDown();
                    assertThat(disponibilidade.get(10, TimeUnit.SECONDS).dataDisponibilidade()).isEqualTo(DATA);
                    assertThatThrownBy(() -> tentativa.get(10, TimeUnit.SECONDS))
                            .hasRootCauseMessage("Depreciação pendente por ativo e competência: EM-USO (2026-05)");
                    assertThat(executar(schema, () -> apropriar(c, ativo.id())).valor()).isZero();
                    executar(schema, () -> fechamento.encerrarPeriodo(c.empresa(), c.usuario(), c.exercicio(), c.periodo()));
                } finally {
                    liberar.countDown();
                    executor.shutdownNow();
                }
            }
        } finally {
            removerSchema(schema);
        }
    }

    @Test
    void cadastroRetroativoAguardaFechamentoERevalidaCalendario() throws Exception {
        String schema = novoSchema();
        try {
            migrar(schema, null);
            Cenario c = executar(schema, this::criarCenario);
            try (var executor = Executors.newFixedThreadPool(2)) {
                var fechado = new CompletableFuture<Integer>();
                var tentando = new CompletableFuture<Integer>();
                var liberar = new CountDownLatch(1);
                try {
                    var fechamentoPendente = executor.submit(() -> executar(schema, () -> {
                        var resultado = fechamento.encerrarPeriodo(c.empresa(), c.usuario(), c.exercicio(), c.periodo());
                        fechado.complete(pid());
                        aguardar(liberar);
                        return resultado;
                    }));
                    int bloqueadora = fechado.get(10, TimeUnit.SECONDS);
                    var inclusao = executor.submit(() -> executar(schema, () -> {
                        tentando.complete(pid());
                        return jdbc.update("""
                                insert into periodo_contabil (exercicio_id, empresa_id, ano, mes, data_inicio, data_fim)
                                values (?, ?, 2026, 4, date '2026-04-01', date '2026-04-30')
                                """, c.exercicio(), c.empresa());
                    }));
                    aguardarBloqueio(bloqueadora, tentando.get(5, TimeUnit.SECONDS), "periodo_contabil");
                    liberar.countDown();
                    assertThat(fechamentoPendente.get(10, TimeUnit.SECONDS).periodoId()).isEqualTo(c.periodo());
                    assertThatThrownBy(() -> inclusao.get(10, TimeUnit.SECONDS))
                            .isInstanceOfSatisfying(ExecutionException.class, erro ->
                                    conferirViolacaoPostgres(erro.getCause(), "23514", "ck_periodo_calendario_encerrado"));
                } finally {
                    liberar.countDown();
                    executor.shutdownNow();
                }
            }
        } finally {
            removerSchema(schema);
        }
    }

    private Cenario periodo(String schema, Cenario c, YearMonth mes) {
        return executar(schema, () -> {
            PeriodoContabil periodo = new PeriodoContabil(em.getReference(ExercicioContabil.class, c.exercicio()),
                    (short) mes.getMonthValue(), mes.atDay(1), mes.atEndOfMonth());
            em.persist(periodo);
            return new Cenario(c.empresa(), c.usuario(), c.exercicio(), periodo.getId(), c.plano(), c.contas());
        });
    }

    private ApropriacaoDepreciacao apropriar(Cenario c, UUID ativoId) {
        return depreciacao.apropriar(c.empresa(), c.usuario(), c.exercicio(), c.periodo(), ativoId);
    }

    private void conferirViolacaoPostgres(Throwable erro, String sqlState, String constraint) {
        assertThat(erro).isInstanceOfSatisfying(DataIntegrityViolationException.class, violacao ->
                assertThat(violacao.getMostSpecificCause()).isInstanceOfSatisfying(PSQLException.class, causa -> {
                    assertThat(causa.getSQLState()).isEqualTo(sqlState);
                    assertThat(causa.getServerErrorMessage()).isNotNull();
                    assertThat(causa.getServerErrorMessage().getConstraint()).isEqualTo(constraint);
                }));
    }

    @Test
    void conciliaCustoEDepreciacaoAteOMesJaEscrituradoNaDataBase() {
        comSchema(schema -> {
            migrar(schema, null);
            Cenario maio = executar(schema, this::criarCenario);
            Cenario junho = periodo(schema, maio, YearMonth.of(2026, 6));
            var ativo = executar(schema, () -> ativos.registrarAquisicao(aquisicao(maio, "CORTE",
                    maio.conta(MAQUINAS), maio.conta(CAIXA), DATA, DATA, "12", true, "2", 3, maio.conta(DESPESA))));
            executar(schema, () -> apropriar(maio, ativo.id()));
            executar(schema, () -> apropriar(junho, ativo.id()));

            assertThat(conciliarConta(schema, maio, maio.exercicio(), DATA.minusDays(1), MAQUINAS)
                    .saldoOperacional()).isZero();
            var custo = conciliarConta(schema, maio, maio.exercicio(), DATA, MAQUINAS);
            assertThat(custo.modulo()).isEqualTo(Modulo.IMOBILIZADO);
            assertThat(custo.saldoOperacional()).isEqualByComparingTo("12.00");
            assertThat(custo.diferenca()).isZero();
            assertThat(conciliarConta(schema, maio, maio.exercicio(), DATA.withDayOfMonth(30), REDUTORA)
                    .saldoOperacional()).isZero();
            var acumulada = conciliarConta(schema, maio, maio.exercicio(), DATA.withDayOfMonth(31), REDUTORA);
            assertThat(acumulada.modulo()).isEqualTo(Modulo.DEPRECIACAO_ACUMULADA);
            assertThat(acumulada.saldoContabil()).isEqualByComparingTo("3.33");
            assertThat(acumulada.saldoOperacional()).isEqualByComparingTo("3.33");
            assertThat(acumulada.diagnostico()).isEqualTo(Diagnostico.CONCILIADO);
        });
    }

    @Test
    void informaPatrimonioDeMigracaoSemFichaEImpedeEncerramentoAnualPorDiferencaAdicional() {
        comSchema(schema -> {
            migrar(schema, null);
            Cenario c = executar(schema, this::criarCenario);
            var abertura = executar(schema, () -> lancamentos.registrarSaldosIniciais(new AberturaContabil(
                    c.empresa(), c.usuario(), c.exercicio(), c.periodo(), DATA, "Migração patrimonial",
                    List.of(new SaldoPorConta(c.conta(MAQUINAS), TipoPartida.DEBITO, new BigDecimal("100")),
                            new SaldoPorConta(c.conta(REDUTORA), TipoPartida.CREDITO, new BigDecimal("20")),
                            new SaldoPorConta(c.conta("3.1.001"), TipoPartida.CREDITO, new BigDecimal("80"))))));
            var ativo = executar(schema, () -> ativos.registrarAquisicao(aquisicao(c, "NOVO",
                    c.conta(MAQUINAS), c.conta(CAIXA), DATA, DATA, "12", true, "0", 1, c.conta(DESPESA))));
            executar(schema, () -> apropriar(c, ativo.id()));
            LocalDate fimMaio = DATA.withDayOfMonth(31);
            var custo = conciliarConta(schema, c, c.exercicio(), fimMaio, MAQUINAS);
            assertThat(custo.diferenca()).isEqualByComparingTo("100");
            assertThat(custo.diagnostico()).isEqualTo(Diagnostico.SALDO_PENDENTE_DE_IMPORTACAO);
            var acumulada = conciliarConta(schema, c, c.exercicio(), fimMaio, REDUTORA);
            assertThat(acumulada.diferenca()).isEqualByComparingTo("20");
            assertThat(acumulada.diagnostico()).isEqualTo(Diagnostico.SALDO_PENDENTE_DE_IMPORTACAO);
            assertThatThrownBy(() -> executar(schema, () ->
                    fechamento.encerrarPeriodo(c.empresa(), c.usuario(), c.exercicio(), c.periodo())))
                    .hasMessageContaining("pendente de importação 20.00");
            importar(schema, c, abertura.lancamentoId(), bemImportado(c, "HISTORICO", "100", "80", 1, "20", 1, false));
            executar(schema, () -> fechamento.encerrarPeriodo(c.empresa(), c.usuario(), c.exercicio(), c.periodo()));

            Cenario dezembro = periodo(schema, c, YearMonth.of(2026, 12));
            LocalDate fimAno = LocalDate.of(2026, 12, 31);
            executar(schema, () -> {
                CalendarioTestFixture.preencherEncerradosAteNovembro(jdbc, c.exercicio(), 6);
                var manual = lancamentos.criarRascunho(c.empresa(), c.usuario(), c.exercicio(), dezembro.periodo(),
                        fimAno, "Ajuste de redutora");
                lancamentos.adicionarPartida(c.empresa(), c.usuario(), manual.getId(), c.conta(DESPESA),
                        TipoPartida.DEBITO, BigDecimal.ONE, null);
                lancamentos.adicionarPartida(c.empresa(), c.usuario(), manual.getId(), c.conta(REDUTORA),
                        TipoPartida.CREDITO, BigDecimal.ONE, null);
                return lancamentos.confirmarLancamento(c.empresa(), c.usuario(), manual.getId());
            });

            assertThat(conciliarConta(schema, c, c.exercicio(), fimAno, REDUTORA).diagnostico())
                    .isEqualTo(Diagnostico.DIVERGENCIA_LANCAMENTO_MANUAL);
            assertThatThrownBy(() -> executar(schema, () -> fechamento.encerrarExercicio(
                    c.empresa(), c.usuario(), c.exercicio())))
                    .hasMessageContaining(REDUTORA).hasMessageContaining("diferença 1.00");
            executar(schema, () -> {
                assertThat(jdbc.queryForObject("select status from exercicio_contabil where id = ?", String.class,
                        c.exercicio())).isEqualTo("ABERTO");
                assertThat(jdbc.queryForObject("select count(*) from exercicio_contabil where empresa_id = ?",
                        Long.class, c.empresa())).isEqualTo(1L);
                return null;
            });
        });
    }

    @Test
    void importaEmAberturaLegadaDaV13EConciliaHistoricoSemNovaEscrituracao() {
        comSchema(schema -> {
            migrar(schema, "13");
            Cenario c = executar(schema, this::criarCenario);
            UUID abertura = abrirPatrimonio(schema, c, DATA, "100", "23.17", "0");
            migrar(schema, null);
            var bem = bemImportado(c, "LEGADO", "100", "10", 12, "23.17", 4, false);
            var ficha = importar(schema, c, abertura, bem).getFirst();
            assertThat(ficha.origem()).isEqualTo(FichaAtivo.Origem.IMPORTACAO);
            assertThat(ficha.lancamentoAberturaId()).isEqualTo(abertura);
            assertThat(ficha.exercicioAquisicaoId()).isNull();
            assertThat(ficha.depreciacaoAcumuladaImportada()).isEqualByComparingTo("23.17");
            assertThat(ficha.depreciacaoApropriadaLex()).isZero();
            assertThat(ficha.valorContabilLiquido()).isEqualByComparingTo("76.83");
            assertThat(ficha.baseDepreciavelRemanescente()).isEqualByComparingTo("66.83");
            assertThat(ficha.vidaUtilRemanescenteMeses()).isEqualTo(8);
            assertThat(ficha.apropriacoes()).isEmpty();
            assertThat(conciliarConta(schema, c, c.exercicio(), DATA.minusDays(1), MAQUINAS).saldoOperacional()).isZero();
            assertThat(conciliarConta(schema, c, c.exercicio(), DATA.minusDays(1), REDUTORA).saldoOperacional()).isZero();
            assertThat(conciliarConta(schema, c, c.exercicio(), DATA, REDUTORA).diagnostico()).isEqualTo(Diagnostico.CONCILIADO);
            assertThatThrownBy(() -> executar(schema, () -> lancamentos.criarRascunhoEstorno(
                    c.empresa(), c.usuario(), abertura, c.exercicio(), c.periodo(), DATA, "Estorno")))
                    .hasMessageContaining("fichas importadas");
            executar(schema, () -> {
                assertThat(jdbc.queryForObject("select ultimo_numero from sequencia_lancamento", Long.class)).isEqualTo(1L);
                assertThat(jdbc.queryForObject("""
                        select count(*) from auditoria_evento where empresa_id = ? and entidade_id = ?
                        and acao = 'IMPORTAR_SALDO_INICIAL' and dados_novos->>'aberturaId' = ?
                        """, Long.class, c.empresa(), ficha.id(), abertura.toString())).isEqualTo(1L);
                var livro = diario.emitir(c.empresa(), c.usuario(), c.exercicio(), DATA, DATA);
                assertThat(livro.lancamentos()).singleElement().satisfies(l -> {
                    assertThat(l.referencia().id()).isEqualTo(abertura);
                    assertThat(l.origem()).isEqualTo(OrigemLancamento.ABERTURA);
                });
                return null;
            });
            LocalDate fim = DATA.withDayOfMonth(31);
            var apropriacao = executar(schema, () -> apropriar(c, ficha.id()));
            assertThat(apropriacao.valor()).isEqualByComparingTo("8.35");
            assertThat(apropriacao.numeroContabil()).isEqualTo(2L);
            executar(schema, () -> {
                var atual = ativos.consultar(c.empresa(), c.usuario(), ficha.id());
                assertThat(atual.depreciacaoAcumulada()).isEqualByComparingTo("31.52");
                assertThat(atual.depreciacaoApropriadaLex()).isEqualByComparingTo("8.35");
                assertThat(atual.apropriacoes()).containsExactly(apropriacao);
                var saldoRazao = razao.emitir(c.empresa(), c.usuario(), c.exercicio(), c.conta(REDUTORA), DATA, fim)
                        .saldos().saldoFinal().valor();
                assertThat(saldoRazao).isEqualByComparingTo("-31.52");
                assertThat(balancete.emitir(c.empresa(), c.usuario(), c.exercicio(), DATA, fim).linhas())
                        .filteredOn(l -> l.conta().id().equals(c.conta(REDUTORA))).singleElement()
                        .satisfies(l -> assertThat(l.saldos().saldoFinal().valor()).isEqualByComparingTo(saldoRazao));
                var bp = balanco.emitir(c.empresa(), c.usuario(), c.exercicio(), fim);
                assertThat(bp.secoes().stream().flatMap(s -> s.linhas().stream())
                        .filter(l -> List.of(c.conta(MAQUINAS), c.conta(REDUTORA)).contains(l.conta().id()))
                        .map(l -> l.valorApresentado()).reduce(BigDecimal.ZERO, BigDecimal::add))
                        .isEqualByComparingTo("68.48");
                assertThat(dre.emitir(c.empresa(), c.usuario(), c.exercicio(), DATA, fim).linhas())
                        .filteredOn(l -> l.rubrica() == Rubrica.DESPESAS_OPERACIONAIS).singleElement()
                        .satisfies(l -> assertThat(l.valorDemonstrado()).isEqualByComparingTo("-8.35"));
                return null;
            });
        });
    }

    @Test
    void continuaNoExercicioSeguinteSemReapropriarOMesDaAbertura() {
        comSchema(schema -> {
            migrar(schema, null);
            Cenario c = executar(schema, this::criarCenario);
            Cenario dezembro = periodo(schema, c, YearMonth.of(2026, 12));
            LocalDate fim = LocalDate.of(2026, 12, 31);
            UUID abertura = abrirPatrimonio(schema, dezembro, fim, "15", "4.50", "0");
            var ficha = importar(schema, dezembro, abertura,
                    bemImportado(c, "CONTINUA", "15", "3", 5, "4.50", 3, true)).getFirst();
            assertThat(ficha.primeiraCompetenciaLex()).isEqualTo(YearMonth.of(2027, 1));
            assertThatThrownBy(() -> executar(schema, () -> apropriar(dezembro, ficha.id())))
                    .hasMessageContaining("próxima competência do ativo é 2027-01");
            assertThatThrownBy(() -> executar(schema, () -> jdbc.update("""
                    insert into apropriacao_depreciacao
                        (id, empresa_id, ativo_id, exercicio_id, periodo_id, usuario_id, competencia, valor)
                    values (?, ?, ?, ?, ?, ?, ?, 0)
                    """, UUID.randomUUID(), c.empresa(), ficha.id(), c.exercicio(), dezembro.periodo(),
                    c.usuario(), fim.withDayOfMonth(1))))
                    .satisfies(erro -> conferirViolacaoPostgres(erro, "23514", "ck_depreciacao_marco_importacao"));
            var transporte = executar(schema, () -> fechamento.encerrarExercicio(c.empresa(), c.usuario(), c.exercicio()));
            UUID novoExercicio = transporte.exercicioDestinoId();
            executar(schema, () -> {
                UUID janeiro = jdbc.queryForObject("select id from periodo_contabil where exercicio_id=? and mes=1",
                        UUID.class, novoExercicio);
                var lote = depreciacao.processarCompetencia(c.empresa(), c.usuario(), novoExercicio, janeiro);
                assertThat(lote.processados()).singleElement().satisfies(a -> {
                    assertThat(a.ativoId()).isEqualTo(ficha.id());
                    assertThat(a.valor()).isEqualByComparingTo("3.75");
                    assertThat(a.numeroContabil()).isEqualTo(2L);
                });
                return null;
            });
            var redutora = conciliarConta(schema, c, novoExercicio, LocalDate.of(2027, 1, 31), REDUTORA);
            assertThat(redutora.saldoOperacional()).isEqualByComparingTo("8.25");
            assertThat(redutora.diagnostico()).isEqualTo(Diagnostico.CONCILIADO);
        });
    }

    @Test
    void importaTerrenoEBemIndisponivelEExigeCompetenciasDeValorZeroDoBemElegivel() {
        comSchema(schema -> {
            migrar(schema, null);
            Cenario c = executar(schema, this::criarCenario);
            UUID abertura = abrirPatrimonio(schema, c, DATA, "3.04", "0.01", "50");
            var terreno = new SaldoInicialPatrimonial.Bem("TER", "Terreno", c.conta(TERRENOS), null,
                    DATA.minusYears(1), null, new BigDecimal("50"), false, new BigDecimal("50"), null,
                    BigDecimal.ZERO, 0, false);
            var indisponivel = new SaldoInicialPatrimonial.Bem("IND", "Sem disponibilidade", c.conta(MAQUINAS),
                    c.conta(DESPESA), DATA.minusYears(1), null, new BigDecimal("3"), true, BigDecimal.ONE,
                    2, BigDecimal.ZERO, 0, false);
            var fichas = importar(schema, c, abertura, terreno, indisponivel,
                    bemImportado(c, "CENTAVO", "0.04", "0.02", 6, "0.01", 1, false));
            assertThat(fichas.get(0).depreciavel()).isFalse();
            assertThat(fichas.get(1).primeiraCompetenciaLex()).isNull();
            var centavo = fichas.get(2);
            assertThatThrownBy(() -> executar(schema, () ->
                    fechamento.encerrarPeriodo(c.empresa(), c.usuario(), c.exercicio(), c.periodo())))
                    .hasMessageContaining("CENTAVO (2026-05)");
            assertThat(executar(schema, () -> apropriar(c, centavo.id())).lancamentoId()).isNull();
            assertThatThrownBy(() -> executar(schema, () -> apropriar(c, centavo.id())))
                    .hasMessageContaining("já");
            executar(schema, () -> fechamento.encerrarPeriodo(c.empresa(), c.usuario(), c.exercicio(), c.periodo()));
            var junho = periodo(schema, c, YearMonth.of(2026, 6));
            assertThat(executar(schema, () -> apropriar(junho, centavo.id())).valor()).isZero();
            var julho = periodo(schema, c, YearMonth.of(2026, 7));
            assertThat(executar(schema, () -> apropriar(julho, centavo.id())).valor()).isEqualByComparingTo("0.01");
            var ficha = executar(schema, () -> ativos.consultar(c.empresa(), c.usuario(), centavo.id()));
            assertThat(ficha.valorContabilLiquido()).isEqualByComparingTo("0.02");
            assertThat(ficha.baseDepreciavelRemanescente()).isZero();
            var disponivel = executar(schema, () -> ativos.registrarDisponibilidade(c.empresa(), c.usuario(),
                    c.exercicio(), julho.periodo(), fichas.get(1).id(), LocalDate.of(2026, 7, 10)));
            assertThat(disponivel.primeiraCompetenciaLex()).isEqualTo(YearMonth.of(2026, 7));
        });
    }

    @Test
    void limitaImportacoesIncrementaisPorContaERejeitaDuplicacaoOuOutraEmpresa() {
        comSchema(schema -> {
            migrar(schema, null);
            Cenario c = executar(schema, this::criarCenario);
            Cenario outra = executar(schema, this::criarCenario);
            UUID abertura = abrirPatrimonio(schema, c, DATA, "100", "20", "50");
            var primeiro = bemImportado(c, "MAQUINA", "100", "0", 12, "20", 4, false);
            assertThatThrownBy(() -> importar(schema, outra, abertura, primeiro)).hasMessageContaining("empresa");
            assertThatThrownBy(() -> importar(schema, c, abertura,
                    bemImportado(c, "CUSTO", "100.01", "0", 12, "20", 4, false)))
                    .hasMessageContaining(c.conta(MAQUINAS).toString());
            assertThatThrownBy(() -> importar(schema, c, abertura,
                    bemImportado(c, "REDUTORA", "100", "0", 12, "19.99", 4, false)))
                    .hasMessageContaining(c.conta(REDUTORA).toString());
            assertThatThrownBy(() -> importar(schema, c, abertura,
                    bemImportado(c, "HISTORICO", "100", "0", 12, "20", 0, false)))
                    .hasMessageContaining("Depreciação acumulada incompatível");
            importar(schema, c, abertura, primeiro);
            assertThatThrownBy(() -> importar(schema, c, abertura, primeiro)).hasMessageContaining("códigos");
            assertThatThrownBy(() -> importar(schema, c, abertura,
                    bemImportado(c, "OUTRA", "100", "0", 12, "20", 4, false)))
                    .hasMessageContaining("saldo disponível da abertura");
            assertThat(conciliarConta(schema, c, c.exercicio(), DATA, MAQUINAS).diagnostico()).isEqualTo(Diagnostico.CONCILIADO);
            assertThat(conciliarConta(schema, c, c.exercicio(), DATA, REDUTORA).diagnostico()).isEqualTo(Diagnostico.CONCILIADO);
            assertThat(conciliarConta(schema, c, c.exercicio(), DATA, TERRENOS).saldoPendenteDeImportacao())
                    .isEqualByComparingTo("50");
            importar(schema, c, abertura, new SaldoInicialPatrimonial.Bem("TERRENO", "Terreno", c.conta(TERRENOS), null,
                    DATA.minusYears(1), null, new BigDecimal("50"), false, new BigDecimal("50"), null,
                    BigDecimal.ZERO, 0, false));
            assertThat(conciliarConta(schema, c, c.exercicio(), DATA, TERRENOS).diagnostico()).isEqualTo(Diagnostico.CONCILIADO);
        });
    }

    @Test
    void reverteLoteDeFichasEEventosQuandoAAuditoriaFalha() {
        comSchema(schema -> {
            migrar(schema, null);
            Cenario c = executar(schema, this::criarCenario);
            UUID abertura = abrirPatrimonio(schema, c, DATA, "100", "20", "0");
            executar(schema, () -> {
                jdbc.execute("""
                        alter table auditoria_evento add constraint teste_bloquear_importacao_patrimonial
                        check (acao <> 'IMPORTAR_SALDO_INICIAL' or dados_novos->>'codigo' <> 'FALHA')
                        """);
                return null;
            });
            var primeiro = bemImportado(c, "PRIMEIRO", "40", "0", 12, "10", 4, false);
            var segundo = bemImportado(c, "FALHA", "60", "0", 12, "10", 4, false);
            assertThatThrownBy(() -> importar(schema, c, abertura, primeiro, segundo))
                    .satisfies(erro -> conferirViolacaoPostgres(erro, "23514", "teste_bloquear_importacao_patrimonial"));
            executar(schema, () -> {
                assertThat(ativos.listar(c.empresa(), c.usuario())).isEmpty();
                assertThat(jdbc.queryForObject("select count(*) from auditoria_evento where entidade = 'ativo_patrimonial'",
                        Long.class)).isZero();
                assertThat(jdbc.queryForObject("select ultimo_numero from sequencia_lancamento", Long.class)).isEqualTo(1L);
                return null;
            });
            assertThat(importar(schema, c, abertura, primeiro,
                    bemImportado(c, "SEGUNDO", "60", "0", 12, "10", 4, false))).hasSize(2);
        });
    }

    @Test
    void processamentoColetivoAguardaImportacaoConcorrenteAntesDeSelecionarAtivos() throws Exception {
        String schema = novoSchema();
        try {
            migrar(schema, null);
            Cenario c = executar(schema, this::criarCenario);
            UUID abertura = abrirPatrimonio(schema, c, DATA, "100", "20", "0");
            var bem = bemImportado(c, "CONCORRENTE", "100", "0", 12, "20", 4, false);
            var executor = Executors.newFixedThreadPool(2);
            var pronta = new CompletableFuture<Integer>();
            var tentando = new CompletableFuture<Integer>();
            var liberar = new CountDownLatch(1);
            try {
                var importacao = executor.submit(() -> executar(schema, () -> {
                    var ficha = ativos.importarSaldoInicial(
                            new SaldoInicialPatrimonial(c.empresa(), c.usuario(), abertura, List.of(bem))).getFirst();
                    pronta.complete(pid());
                    aguardar(liberar);
                    return ficha;
                }));
                int bloqueadora = pronta.get(10, TimeUnit.SECONDS);
                var processamento = executor.submit(() -> executar(schema, () -> {
                    tentando.complete(pid());
                    return processar(c);
                }));
                aguardarBloqueio(bloqueadora, tentando.get(5, TimeUnit.SECONDS), "exercicio_contabil");
                liberar.countDown();
                UUID ativoId = importacao.get(10, TimeUnit.SECONDS).id();
                assertThat(processamento.get(10, TimeUnit.SECONDS).processados()).singleElement().satisfies(a -> {
                    assertThat(a.ativoId()).isEqualTo(ativoId);
                    assertThat(a.valor()).isEqualByComparingTo("10.00");
                });
            } finally {
                liberar.countDown();
                executor.shutdownNow();
                assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
            }
        } finally {
            removerSchema(schema);
        }
    }

    private UUID abrirPatrimonio(String schema, Cenario c, LocalDate data, String maquinas, String redutora, String terreno) {
        List<SaldoPorConta> partidas = new ArrayList<>();
        partidas.add(new SaldoPorConta(c.conta(MAQUINAS), TipoPartida.DEBITO, new BigDecimal(maquinas)));
        if (new BigDecimal(redutora).signum() > 0) {
            partidas.add(new SaldoPorConta(c.conta(REDUTORA), TipoPartida.CREDITO, new BigDecimal(redutora)));
        }
        if (new BigDecimal(terreno).signum() > 0) {
            partidas.add(new SaldoPorConta(c.conta(TERRENOS), TipoPartida.DEBITO, new BigDecimal(terreno)));
        }
        var capital = new BigDecimal(maquinas).add(new BigDecimal(terreno)).subtract(new BigDecimal(redutora));
        partidas.add(new SaldoPorConta(c.conta("3.1.001"), TipoPartida.CREDITO, capital));
        return executar(schema, () -> lancamentos.registrarSaldosIniciais(new AberturaContabil(c.empresa(), c.usuario(),
                c.exercicio(), c.periodo(), data, "Abertura patrimonial", partidas))).lancamentoId();
    }

    private SaldoInicialPatrimonial.Bem bemImportado(Cenario c, String codigo, String custo, String residual,
                                                     int vida, String acumulado, int consumidos, boolean mesApropriado) {
        LocalDate historico = DATA.minusYears(1);
        return new SaldoInicialPatrimonial.Bem(codigo, "Bem existente", c.conta(MAQUINAS), c.conta(DESPESA),
                historico, historico, new BigDecimal(custo), true, new BigDecimal(residual), vida,
                new BigDecimal(acumulado), consumidos, mesApropriado);
    }

    private List<FichaAtivo> importar(String schema, Cenario c, UUID abertura, SaldoInicialPatrimonial.Bem... bens) {
        return executar(schema, () -> ativos.importarSaldoInicial(
                new SaldoInicialPatrimonial(c.empresa(), c.usuario(), abertura, List.of(bens))));
    }

    private Item conciliarConta(String schema, Cenario c, UUID exercicio, LocalDate data, String codigo) {
        var tx = new TransactionTemplate(transacoes);
        tx.setReadOnly(true);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return tx.execute(status -> {
            jdbc.execute("set local search_path to " + schema);
            return conciliacao.conciliar(c.empresa(), c.usuario(), exercicio, data).itens().stream()
                    .filter(item -> item.conta().id().equals(c.conta(codigo))).findFirst().orElseThrow();
        });
    }

    private AquisicaoAtivo maquina(Cenario c, String codigo, String contrapartida) {
        return aquisicao(c, codigo, c.conta(MAQUINAS), c.conta(contrapartida), DATA, DATA.plusDays(1),
                "1200", true, "200", 24, c.conta(DESPESA));
    }

    // O schema de origem desses upgrades contém somente as colunas patrimoniais da V9.
    private UUID semearAquisicaoLegada(Cenario c, AquisicaoAtivo dados) {
        var lancamento = lancamentos.escriturarTecnico(em.getReference(Empresa.class, c.empresa()),
                em.getReference(Usuario.class, c.usuario()), em.getReference(ExercicioContabil.class, c.exercicio()),
                em.getReference(PeriodoContabil.class, c.periodo()), dados.dataAquisicao(), dados.historico(),
                OrigemLancamento.ATIVO, List.of(
                        new SaldoPorConta(dados.contaPatrimonialId(), TipoPartida.DEBITO, dados.custoAquisicao()),
                        new SaldoPorConta(dados.contrapartidaId(), TipoPartida.CREDITO, dados.custoAquisicao())));
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into ativo_patrimonial (id, empresa_id, plano_contas_id, usuario_id, codigo, descricao,
                    conta_patrimonial_id, conta_redutora_id, conta_despesa_id, data_aquisicao, data_disponibilidade,
                    custo_aquisicao, depreciavel, valor_residual, vida_util_meses, lancamento_aquisicao_id)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, c.empresa(), c.plano(), c.usuario(), dados.codigo(), dados.descricao(), dados.contaPatrimonialId(),
                c.conta(REDUTORA), dados.contaDespesaId(), dados.dataAquisicao(), dados.dataDisponibilidade(),
                dados.custoAquisicao(), dados.depreciavel(), dados.valorResidual(), dados.vidaUtilMeses(), lancamento.getId());
        jdbc.update("""
                insert into auditoria_evento (empresa_id, usuario_id, entidade, entidade_id, acao, dados_novos)
                values (?, ?, 'ativo_patrimonial', ?, 'AQUISICAO', '{}'::jsonb)
                """, c.empresa(), c.usuario(), id);
        return id;
    }

    private AquisicaoAtivo aquisicao(Cenario c, String codigo, UUID bem, UUID contrapartida, LocalDate data,
                                    LocalDate disponibilidade, String custo, boolean depreciavel, String residual,
                                    Integer meses, UUID despesa) {
        return new AquisicaoAtivo(c.empresa(), c.usuario(), c.exercicio(), c.periodo(), codigo, "Bem patrimonial",
                bem, contrapartida, data, disponibilidade, new BigDecimal(custo), depreciavel, new BigDecimal(residual),
                meses, despesa, "Aquisição patrimonial");
    }

    private void rejeitar(String schema, AquisicaoAtivo aquisicao, String mensagem) {
        assertThatThrownBy(() -> executar(schema, () -> ativos.registrarAquisicao(aquisicao)))
                .hasMessageContaining(mensagem);
    }

    private Cenario criarCenario() {
        Usuario usuario = new Usuario("Titular", UUID.randomUUID() + "@example.test", "hash-teste");
        em.persist(usuario);
        Empresa empresa = new Empresa(usuario, "Empresa", "Empresa", null);
        em.persist(empresa);
        ExercicioContabil exercicio = new ExercicioContabil(empresa, (short) 2026,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
        em.persist(exercicio);
        PeriodoContabil periodo = new PeriodoContabil(exercicio, (short) 5, DATA.withDayOfMonth(1), DATA.withDayOfMonth(31));
        em.persist(periodo);
        em.flush();
        UUID plano = planos.provisionar(empresa.getId(), usuario.getId()).getId();
        return new Cenario(empresa.getId(), usuario.getId(), exercicio.getId(), periodo.getId(), plano,
                contas.findAllByPlanoContasIdOrderByCodigoAsc(plano).stream()
                        .collect(toMap(c -> c.getCodigo(), c -> c.getId())));
    }

    private String novoSchema() {
        return "lex_ativo_" + UUID.randomUUID().toString().replace("-", "");
    }

    private void comSchema(Consumer<String> teste) {
        String schema = novoSchema();
        try {
            teste.accept(schema);
        } finally {
            removerSchema(schema);
        }
    }

    private void removerSchema(String schema) {
        jdbc.execute("drop schema if exists " + schema + " cascade");
    }

    private void migrar(String schema, String alvo) {
        var config = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration");
        if (alvo != null) {
            config.target(alvo);
        }
        config.load().migrate();
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

    private void aguardar(CountDownLatch liberar) {
        try {
            assertThat(liberar.await(12, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private record Cenario(UUID empresa, UUID usuario, UUID exercicio, UUID periodo, UUID plano, Map<String, UUID> contas) {
        UUID conta(String codigo) {
            return contas.get(codigo);
        }
    }
}
