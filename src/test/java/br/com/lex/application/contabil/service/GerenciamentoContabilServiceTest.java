package br.com.lex.application.contabil.service;

import br.com.lex.application.contabil.dto.AberturaContabil;
import br.com.lex.application.contabil.dto.AberturaContabil.SaldoPorConta;
import br.com.lex.application.contabil.dto.CalendarioContabil.Competencia;
import br.com.lex.application.contabil.dto.CalendarioContabil.Exercicio;
import br.com.lex.application.contabil.dto.EmpresaCadastrada.Dados;
import br.com.lex.application.contabil.dto.PlanoContabil;
import br.com.lex.domain.contabil.entity.Empresa;
import br.com.lex.domain.contabil.entity.Usuario;
import br.com.lex.domain.contabil.enums.StatusExercicio;
import br.com.lex.domain.contabil.enums.StatusLancamento;
import br.com.lex.domain.contabil.enums.TipoPartida;
import br.com.lex.domain.contabil.repository.EmpresaRepository;
import br.com.lex.domain.contabil.repository.UsuarioRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ActiveProfiles("test")
@SpringBootTest
class GerenciamentoContabilServiceTest {

    private static final Dados EMPRESA = new Dados("  Empresa Acadêmica  ", "LEX", null);
    private static final YearMonth OUTUBRO = YearMonth.of(2026, 10);

    @Autowired private EmpresaService empresas;
    @Autowired private CalendarioContabilService calendario;
    @Autowired private PlanoContasService planos;
    @Autowired private LancamentoContabilService lancamentos;
    @Autowired private FechamentoContabilService fechamento;
    @Autowired private UsuarioRepository usuarios;
    @Autowired private EmpresaRepository empresasRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transacoes;
    @Autowired private DataSource dataSource;

    @Test
    @Transactional
    void cadastraEmpresaSemCnpjComModeloECompetenciasDesdeOInicio() {
        UUID usuario = usuario().getId();
        var empresa = empresas.cadastrar(usuario, EMPRESA, OUTUBRO);
        var plano = planos.consultar(empresa.id(), usuario);
        var caixa = conta(plano, "1.1.01.001");
        var capital = conta(plano, "3.1.001");
        var redutora = conta(plano, "3.1.002");
        assertThat(empresa.razaoSocial()).isEqualTo("Empresa Acadêmica");
        assertThat(empresa.cnpj()).isNull();
        assertThat(caixa.contaPaiId()).isEqualTo(conta(plano, "1.1.01").id());
        assertThat(redutora.redutora()).isTrue();
        assertThat(redutora.contaReduzidaId()).isEqualTo(capital.id());
        assertThat(plano.contas()).allSatisfy(conta -> assertThat(conta.modeloContaId()).isNotNull());

        Exercicio exercicio = unicoExercicio(empresa.id(), usuario);
        assertThat(exercicio.inicio()).isEqualTo(LocalDate.of(2026, 1, 1));
        var competencias = calendario.listarCompetencias(empresa.id(), usuario, exercicio.id());
        assertThat(competencias).extracting(Competencia::mes).containsExactly((short) 10, (short) 11, (short) 12);
        assertThat(calendario.consultarCompetencia(empresa.id(), usuario, exercicio.id(), competencias.getFirst().id()))
                .isEqualTo(competencias.getFirst());
        assertThat(jdbc.queryForObject("select count(*) from lancamento_contabil where empresa_id = ?",
                Long.class, empresa.id())).isZero();
        assertThat(jdbc.queryForList("select acao from auditoria_evento where empresa_id = ? and usuario_id = ?",
                String.class, empresa.id(), usuario)).containsExactlyInAnyOrder(
                        "CADASTRAR_EMPRESA", "PROVISIONAR_PLANO_CONTAS", "CRIAR_EXERCICIO", "PROVISIONAR_COMPETENCIAS");
    }

    @Test
    @Transactional
    void consultaApenasEmpresasDoTitularEIdsDoExercicioCorreto() {
        UUID titular = usuario().getId();
        UUID outro = usuario().getId();
        var a = empresas.cadastrar(titular, EMPRESA, OUTUBRO);
        var b = empresas.cadastrar(outro, EMPRESA, OUTUBRO);
        var exercicioB = unicoExercicio(b.id(), outro);
        assertThat(empresas.listar(titular)).containsExactly(a);
        assertThat(empresas.consultar(a.id(), titular)).isEqualTo(a);
        assertThatThrownBy(() -> empresas.consultar(b.id(), titular)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> planos.consultar(b.id(), titular)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calendario.listarExercicios(b.id(), titular)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calendario.listarCompetencias(a.id(), titular, exercicioB.id()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> empresas.atualizar(b.id(), titular, EMPRESA)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @Transactional
    void alteraDadosArquivaEReativaPreservandoPlanoCalendarioEAuditoria() {
        UUID titular = usuario().getId();
        var empresa = empresas.cadastrar(titular, EMPRESA, OUTUBRO);
        UUID id = empresa.id();
        var plano = planos.consultar(id, titular);
        var exercicio = unicoExercicio(id, titular);
        Dados editados = new Dados("Razão atualizada", "Fantasia", "11222333000181");
        var atualizado = empresas.atualizar(id, titular, editados);
        assertThat(atualizado.id()).isEqualTo(id);
        assertThat(atualizado.cnpj()).isEqualTo(editados.cnpj());
        var arquivado = empresas.arquivar(id, titular, "  Pausa acadêmica  ");
        assertThat(arquivado.ativa()).isFalse();
        assertThat(arquivado.arquivadaPor()).isEqualTo(titular);
        assertThat(arquivado.motivoArquivamento()).isEqualTo("Pausa acadêmica");
        assertThat(empresas.listar(titular)).extracting(e -> e.id()).containsExactly(id);
        assertThat(planos.consultar(id, titular)).isEqualTo(plano);
        assertThat(calendario.consultarExercicio(id, titular, exercicio.id())).isEqualTo(exercicio);
        assertThatThrownBy(() -> calendario.criarSucessor(id, titular, 2027)).hasMessageContaining("ativa");
        var outubro = calendario.listarCompetencias(id, titular, exercicio.id()).getFirst();
        assertThatThrownBy(() -> lancamentos.criarRascunho(id, titular, exercicio.id(), outubro.id(),
                outubro.inicio(), "Operação com empresa arquivada")).hasMessageContaining("ativa");

        var reativado = empresas.reativar(id, titular);
        assertThat(reativado).isEqualTo(atualizado);
        assertThat(jdbc.queryForObject("""
                select dados_anteriores ->> 'motivoArquivamento' from auditoria_evento
                where empresa_id = ? and usuario_id = ? and acao = 'REATIVAR_EMPRESA'
                """, String.class, id, titular)).isEqualTo("Pausa acadêmica");
        assertThat(jdbc.queryForObject("""
                select dados_novos ->> 'razaoSocial' from auditoria_evento
                where empresa_id = ? and usuario_id = ? and acao = 'ATUALIZAR_EMPRESA'
                """, String.class, id, titular)).isEqualTo(editados.razaoSocial());
    }

    @Test
    @Transactional
    void rejeitaDadosInvalidosUsuarioInativoEExerciciosForaDosLimites() {
        UUID titular = usuario().getId();
        var ultimoAno = empresas.cadastrar(titular, EMPRESA, YearMonth.of(9999, 12));
        assertThatThrownBy(() -> calendario.criarSucessor(ultimoAno.id(), titular, 10000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> empresas.cadastrar(titular, EMPRESA, YearMonth.of(1899, 12)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> empresas.cadastrar(titular, new Dados(" ", null, null), OUTUBRO))
                .hasMessageContaining("Razão social");
        assertThatThrownBy(() -> empresas.cadastrar(titular, new Dados("Nome", null, "abc"), OUTUBRO))
                .hasMessageContaining("14 dígitos");
        UUID inativo = jdbc.queryForObject("""
                insert into usuario(nome, email, senha_hash, ativo) values ('Inativo', ?, 'hash-teste', false)
                returning id
                """, UUID.class, UUID.randomUUID() + "@lex.test");
        assertThatThrownBy(() -> empresas.cadastrar(inativo, EMPRESA, OUTUBRO)).hasMessageContaining("ativo");
    }

    @Test
    @Transactional
    void rejeitaCnpjDuplicadoMesmoEntreTitularesDistintos() {
        UUID titular = usuario().getId();
        UUID outro = usuario().getId();
        String cnpj = "11222333000181";
        var empresa = empresas.cadastrar(outro, EMPRESA, OUTUBRO);
        empresas.cadastrar(titular, new Dados("Empresa com CNPJ", null, cnpj), OUTUBRO);
        assertThatThrownBy(() -> empresas.atualizar(empresa.id(), outro, new Dados("Empresa", null, cnpj)))
                .hasStackTraceContaining("uk_empresa_cnpj");
    }

    @Test
    @Transactional
    void confirmaAberturaMigradaELancamentoETransportaAoSucessorPreparado() {
        UUID titular = usuario().getId();
        UUID empresa = empresas.cadastrar(titular, EMPRESA, OUTUBRO).id();
        Exercicio inicial = unicoExercicio(empresa, titular);
        var periodos = calendario.listarCompetencias(empresa, titular, inicial.id());
        var plano = planos.consultar(empresa, titular);
        UUID caixa = conta(plano, "1.1.01.001").id();
        UUID capital = conta(plano, "3.1.001").id();
        UUID receita = conta(plano, "4.1.001").id();
        LocalDate dataAbertura = OUTUBRO.atDay(10);
        var abertura = lancamentos.registrarSaldosIniciais(new AberturaContabil(empresa, titular, inicial.id(),
                periodos.getFirst().id(), dataAbertura, "Saldos migrados", List.of(
                        new SaldoPorConta(caixa, TipoPartida.DEBITO, new BigDecimal("100.00")),
                        new SaldoPorConta(capital, TipoPartida.CREDITO, new BigDecimal("100.00")))));
        assertThat(abertura.numero()).isEqualTo(1L);
        assertThat(calendario.provisionarCompetencias(empresa, titular, inicial.id(), OUTUBRO)).isEqualTo(periodos);
        assertThatThrownBy(() -> calendario.provisionarCompetencias(empresa, titular, inicial.id(), OUTUBRO.minusMonths(1)))
                .hasMessageContaining("início de escrituração aplicável");
        assertThatThrownBy(() -> calendario.criarSucessor(empresa, titular, 2028))
                .hasMessageContaining("imediatamente seguinte");
        Exercicio sucessor = calendario.criarSucessor(empresa, titular, 2027);
        assertThatThrownBy(() -> calendario.criarSucessor(empresa, titular, 2028))
                .hasMessageContaining("continuidade patrimonial");
        assertThat(sucessor.exercicioOrigemId()).isNull();
        var janeiro = calendario.listarCompetencias(empresa, titular, sucessor.id()).getFirst();
        assertThatThrownBy(() -> fechamento.encerrarPeriodo(empresa, titular, sucessor.id(), janeiro.id()))
                .hasMessageContaining("continuidade do exercício anterior");

        var rascunho = lancamentos.criarRascunho(empresa, titular, inicial.id(), periodos.getFirst().id(),
                dataAbertura.plusDays(1), "Venda");
        lancamentos.adicionarPartida(empresa, titular, rascunho.getId(), caixa, TipoPartida.DEBITO,
                new BigDecimal("25.00"), null);
        lancamentos.adicionarPartida(empresa, titular, rascunho.getId(), receita, TipoPartida.CREDITO,
                new BigDecimal("25.00"), null);
        assertThat(lancamentos.confirmarLancamento(empresa, titular, rascunho.getId()).getStatus())
                .isEqualTo(StatusLancamento.CONFIRMADO);

        fechamento.encerrarPeriodo(empresa, titular, inicial.id(), periodos.get(0).id());
        fechamento.encerrarPeriodo(empresa, titular, inicial.id(), periodos.get(1).id());
        var encerramento = fechamento.encerrarExercicio(empresa, titular, inicial.id());
        assertThat(encerramento.exercicioDestinoId()).isEqualTo(sucessor.id());
        assertThat(calendario.consultarExercicio(empresa, titular, sucessor.id()).exercicioOrigemId())
                .isEqualTo(inicial.id());
        assertThat(calendario.consultarExercicio(empresa, titular, inicial.id()).status())
                .isEqualTo(StatusExercicio.ENCERRADO);
        assertThat(calendario.listarCompetencias(empresa, titular, sucessor.id())).hasSize(12);
        assertThatThrownBy(() -> calendario.provisionarCompetencias(empresa, titular, inicial.id(), OUTUBRO))
                .hasMessageContaining("deve estar aberto");
    }

    @Test
    void reverteEmpresaPlanoContasCalendarioEAuditoriaSeFalharUltimoEvento() {
        comSchema(schema -> {
            UUID titular = executar(schema, () -> {
                jdbc.execute("""
                        alter table auditoria_evento add constraint teste_bloquear_cadastro
                        check (acao <> 'CADASTRAR_EMPRESA')
                        """);
                return usuario().getId();
            });
            assertThatThrownBy(() -> executar(schema, () -> empresas.cadastrar(titular, EMPRESA, OUTUBRO)))
                    .isInstanceOf(DataIntegrityViolationException.class).hasStackTraceContaining("teste_bloquear_cadastro");
            executar(schema, () -> {
                for (String tabela : List.of("empresa", "plano_contas", "conta_contabil", "exercicio_contabil",
                        "periodo_contabil", "auditoria_evento")) {
                    assertThat(jdbc.queryForObject("select count(*) from " + tabela, Long.class))
                            .as(tabela).isZero();
                }
                return null;
            });
        });
    }

    @Test
    void serializaCriacaoInicialEComplementacaoConcorrenteDeCompetencias() {
        comSchema(schema -> {
            UUID titular = executar(schema, () -> usuario().getId());
            UUID empresa = executar(schema, () -> empresasRepository.saveAndFlush(new Empresa(
                    usuarios.getReferenceById(titular), "Empresa pré-existente", null, null)).getId());
            var criacoes = disputar(() -> {
                try {
                    return executar(schema, () -> calendario.criarInicial(empresa, titular, OUTUBRO)).id();
                } catch (IllegalStateException e) {
                    assertThat(e).hasMessageContaining("já possui exercício");
                    return null;
                }
            });
            assertThat(criacoes.stream().filter(id -> id != null).toList()).hasSize(1);
            Exercicio exercicio = executar(schema, () -> unicoExercicio(empresa, titular));
            executar(schema, () -> jdbc.update("delete from periodo_contabil where exercicio_id = ? and mes = 11",
                    exercicio.id()));
            var calendarios = disputar(() -> executar(schema,
                    () -> calendario.provisionarCompetencias(empresa, titular, exercicio.id(), OUTUBRO)));
            assertThat(calendarios.getFirst()).hasSize(3).isEqualTo(calendarios.getLast());
            executar(schema, () -> {
                assertThat(jdbc.queryForObject("select count(*) from periodo_contabil", Long.class)).isEqualTo(3L);
                assertThat(jdbc.queryForObject("""
                        select count(*) from auditoria_evento where acao = 'PROVISIONAR_COMPETENCIAS'
                        """, Long.class)).isEqualTo(2L);
                return null;
            });
        });
    }

    private Usuario usuario() {
        return usuarios.saveAndFlush(new Usuario("Titular", UUID.randomUUID() + "@lex.test", "hash-teste"));
    }

    private Exercicio unicoExercicio(UUID empresa, UUID titular) {
        var exercicios = calendario.listarExercicios(empresa, titular);
        assertThat(exercicios).hasSize(1);
        return exercicios.getFirst();
    }

    private PlanoContabil.Conta conta(PlanoContabil plano, String codigo) {
        return plano.contas().stream().filter(c -> c.codigo().equals(codigo)).findFirst().orElseThrow();
    }

    private <T> List<T> disputar(Callable<T> operacao) {
        var prontas = new CountDownLatch(2);
        try (var threads = Executors.newFixedThreadPool(2)) {
            Callable<T> simultanea = () -> {
                prontas.countDown();
                assertThat(prontas.await(5, TimeUnit.SECONDS)).isTrue();
                return operacao.call();
            };
            var primeira = threads.submit(simultanea);
            var segunda = threads.submit(simultanea);
            return Arrays.asList(primeira.get(20, TimeUnit.SECONDS), segunda.get(20, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        } catch (ExecutionException | TimeoutException e) {
            throw new AssertionError(e);
        }
    }

    private void comSchema(Consumer<String> teste) {
        String schema = "lex_gerenciamento_" + UUID.randomUUID().toString().replace("-", "");
        try {
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                    .locations("classpath:db/migration").load().migrate();
            teste.accept(schema);
        } finally {
            jdbc.execute("drop schema if exists " + schema + " cascade");
        }
    }

    private <T> T executar(String schema, Supplier<T> operacao) {
        var tx = new TransactionTemplate(transacoes);
        tx.setTimeout(18);
        return tx.execute(status -> {
            jdbc.execute("set local search_path to " + schema);
            jdbc.execute("set local statement_timeout = '12s'");
            return operacao.get();
        });
    }
}
