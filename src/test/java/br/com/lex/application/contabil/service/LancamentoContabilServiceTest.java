package br.com.lex.application.contabil.service;

import br.com.lex.domain.contabil.entity.ContaContabil;
import br.com.lex.domain.contabil.entity.Empresa;
import br.com.lex.domain.contabil.entity.ExercicioContabil;
import br.com.lex.domain.contabil.entity.LancamentoContabil;
import br.com.lex.domain.contabil.entity.ModeloContaContabil;
import br.com.lex.domain.contabil.entity.PartidaContabil;
import br.com.lex.domain.contabil.entity.PeriodoContabil;
import br.com.lex.domain.contabil.entity.PlanoContas;
import br.com.lex.domain.contabil.entity.Usuario;
import br.com.lex.domain.contabil.enums.OrigemLancamento;
import br.com.lex.domain.contabil.enums.StatusLancamento;
import br.com.lex.domain.contabil.enums.TipoConta;
import br.com.lex.domain.contabil.enums.TipoPartida;
import br.com.lex.domain.contabil.repository.ContaContabilRepository;
import br.com.lex.domain.contabil.repository.EmpresaRepository;
import br.com.lex.domain.contabil.repository.ExercicioContabilRepository;
import br.com.lex.domain.contabil.repository.LancamentoContabilRepository;
import br.com.lex.domain.contabil.repository.ModeloContaContabilRepository;
import br.com.lex.domain.contabil.repository.ModeloPlanoContasRepository;
import br.com.lex.domain.contabil.repository.PartidaContabilRepository;
import br.com.lex.domain.contabil.repository.PeriodoContabilRepository;
import br.com.lex.domain.contabil.repository.PlanoContasRepository;
import br.com.lex.domain.contabil.repository.UsuarioRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class LancamentoContabilServiceTest {

    private static final LocalDate DATA = LocalDate.of(2026, 1, 15);
    private static final BigDecimal VALOR = new BigDecimal("100.00");

    @Autowired
    private LancamentoContabilService service;
    @Autowired
    private UsuarioRepository usuarios;
    @Autowired
    private EmpresaRepository empresas;
    @Autowired
    private ExercicioContabilRepository exercicios;
    @Autowired
    private PeriodoContabilRepository periodos;
    @Autowired
    private ModeloPlanoContasRepository modelos;
    @Autowired
    private ModeloContaContabilRepository modelosConta;
    @Autowired
    private PlanoContasRepository planos;
    @Autowired
    private ContaContabilRepository contas;
    @Autowired
    private LancamentoContabilRepository lancamentos;
    @Autowired
    private PartidaContabilRepository partidas;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private Cenario cenario;

    @BeforeEach
    void prepararCenario() {
        cenario = criarCenario();
    }

    @Test
    void criaRascunhoManualSemNumeroOuDadosDeConfirmacao() {
        UUID id = criarRascunho(cenario).getId();
        entityManager.flush();
        entityManager.clear();

        LancamentoContabil rascunho = lancamentos.findById(id).orElseThrow();
        assertAll(
                () -> assertEquals(StatusLancamento.RASCUNHO, rascunho.getStatus()),
                () -> assertEquals(OrigemLancamento.MANUAL, rascunho.getOrigem()),
                () -> assertEquals(cenario.empresa().getId(), rascunho.getEmpresaId()),
                () -> assertEquals(cenario.exercicio().getId(), rascunho.getExercicioId()),
                () -> assertEquals(cenario.periodo().getId(), rascunho.getPeriodoId()),
                () -> assertEquals(cenario.plano().getId(), rascunho.getPlanoContasId()),
                () -> assertEquals(cenario.usuario().getId(), rascunho.getCriadoPor().getId()),
                () -> assertEquals(DATA, rascunho.getData()),
                () -> assertEquals("Compra manual", rascunho.getHistorico()),
                () -> assertNull(rascunho.getNumero()),
                () -> assertNull(rascunho.getConfirmadoEm()),
                () -> assertNull(rascunho.getConfirmadoPor()),
                () -> assertNull(rascunho.getEstornoDeId()),
                () -> assertEquals(0, partidas.countByLancamentoId(id))
        );
    }

    @Test
    void rejeitaUsuarioInexistenteOuInativo() {
        assertThrows(IllegalArgumentException.class, () -> service.criarRascunho(
                cenario.empresa().getId(), UUID.randomUUID(), cenario.exercicio().getId(),
                cenario.periodo().getId(), DATA, "Compra"
        ));
        alterarEstado("UPDATE usuario SET ativo = false WHERE id = :id", cenario.usuario().getId());
        assertThrows(IllegalStateException.class, () -> criarRascunho(cenario));
    }

    @Test
    void rejeitaAcessoDeOutroUsuarioEMutacoesForaDaEmpresa() {
        Cenario outro = criarCenario();
        LancamentoContabil rascunho = criarRascunho(cenario);
        PartidaContabil partida = adicionar(cenario, rascunho, cenario.conta(), VALOR);
        LancamentoContabil rascunhoAlheio = criarRascunho(outro);
        PartidaContabil partidaAlheia = adicionar(outro, rascunhoAlheio, outro.conta(), VALOR);

        assertThrows(IllegalArgumentException.class, () -> service.criarRascunho(
                cenario.empresa().getId(), outro.usuario().getId(), cenario.exercicio().getId(),
                cenario.periodo().getId(), DATA, "Compra"
        ));
        assertThrows(IllegalArgumentException.class, () -> service.adicionarPartida(
                cenario.empresa().getId(), outro.usuario().getId(), rascunho.getId(),
                cenario.conta().getId(), TipoPartida.DEBITO, VALOR, null
        ));
        assertThrows(IllegalArgumentException.class, () -> service.removerPartida(
                cenario.empresa().getId(), outro.usuario().getId(), rascunho.getId(), partida.getId()
        ));
        assertThrows(IllegalArgumentException.class, () -> adicionar(outro, rascunho, outro.conta(), VALOR));
        assertThrows(IllegalArgumentException.class, () -> remover(outro, rascunho, partida));
        assertThrows(IllegalArgumentException.class, () -> remover(cenario, rascunho, partidaAlheia));
        assertEquals(1, partidas.countByLancamentoId(rascunho.getId()));
        assertTrue(partidas.existsById(partida.getId()));
        assertTrue(partidas.existsById(partidaAlheia.getId()));
    }

    @Test
    void rejeitaCriacaoEMutacoesParaEmpresaInativa() {
        LancamentoContabil rascunho = criarRascunho(cenario);
        PartidaContabil partida = adicionar(cenario, rascunho, cenario.conta(), VALOR);
        alterarEstado("UPDATE empresa SET ativa = false WHERE id = :id", cenario.empresa().getId());

        assertThrows(IllegalStateException.class, () -> criarRascunho(cenario));
        assertThrows(IllegalStateException.class, () -> adicionar(cenario, rascunho, cenario.conta(), VALOR));
        assertThrows(IllegalStateException.class, () -> remover(cenario, rascunho, partida));
        assertEquals(1, partidas.countByLancamentoId(rascunho.getId()));
    }

    @Test
    void rejeitaExercicioEPeriodoDeOutraEmpresa() {
        Cenario outro = criarCenario();
        assertThrows(IllegalArgumentException.class, () -> service.criarRascunho(
                cenario.empresa().getId(), cenario.usuario().getId(), outro.exercicio().getId(),
                outro.periodo().getId(), DATA, "Compra"
        ));
        assertThrows(IllegalArgumentException.class, () -> service.criarRascunho(
                cenario.empresa().getId(), cenario.usuario().getId(), cenario.exercicio().getId(),
                outro.periodo().getId(), DATA, "Compra"
        ));
    }

    @Test
    void rejeitaPeriodoDeOutroExercicioDaMesmaEmpresa() {
        ExercicioContabil outro = criarExercicio(cenario.empresa(), 2027);
        PeriodoContabil periodo = criarPeriodo(outro, 1);
        assertThrows(IllegalArgumentException.class, () -> service.criarRascunho(
                cenario.empresa().getId(), cenario.usuario().getId(), cenario.exercicio().getId(),
                periodo.getId(), DATA, "Compra"
        ));
    }

    @Test
    void rejeitaCriacaoEmExercicioEncerrado() {
        alterarEstado("UPDATE exercicio_contabil SET status = 'ENCERRADO' WHERE id = :id",
                cenario.exercicio().getId());
        assertThrows(IllegalStateException.class, () -> criarRascunho(cenario));
    }

    @Test
    void rejeitaCriacaoEmPeriodoEncerrado() {
        alterarEstado("UPDATE periodo_contabil SET status = 'ENCERRADO' WHERE id = :id",
                cenario.periodo().getId());
        assertThrows(IllegalStateException.class, () -> criarRascunho(cenario));
    }

    @Test
    void rejeitaDataForaDoPeriodoEHistoricoEmBranco() {
        assertThrows(IllegalArgumentException.class, () -> service.criarRascunho(
                cenario.empresa().getId(), cenario.usuario().getId(), cenario.exercicio().getId(),
                cenario.periodo().getId(), DATA.plusMonths(1), "Compra"
        ));
        assertThrows(IllegalArgumentException.class, () -> service.criarRascunho(
                cenario.empresa().getId(), cenario.usuario().getId(), cenario.exercicio().getId(),
                cenario.periodo().getId(), DATA, " "
        ));
    }

    @Test
    void rejeitaEmpresaSemPlanoDeContas() {
        Empresa empresaSemPlano = empresas.save(new Empresa(cenario.usuario(), "Sem plano", "Sem plano", null));
        ExercicioContabil exercicio = criarExercicio(empresaSemPlano, 2026);
        PeriodoContabil periodo = criarPeriodo(exercicio, 1);
        assertThrows(IllegalStateException.class, () -> service.criarRascunho(
                empresaSemPlano.getId(), cenario.usuario().getId(), exercicio.getId(), periodo.getId(), DATA, "Compra"
        ));
    }

    @Test
    void adicionaPartidaAteOLimiteMonetarioSemExigirEquilibrio() {
        LancamentoContabil rascunho = criarRascunho(cenario);
        BigDecimal valor = new BigDecimal("99999999999999999.990");
        UUID id = service.adicionarPartida(cenario.empresa().getId(), cenario.usuario().getId(),
                rascunho.getId(), cenario.conta().getId(), TipoPartida.DEBITO, valor, " Compra ").getId();
        entityManager.flush();
        entityManager.clear();

        PartidaContabil partida = partidas.findById(id).orElseThrow();
        assertAll(
                () -> assertEquals(rascunho.getId(), partida.getLancamentoId()),
                () -> assertEquals(cenario.conta().getId(), partida.getContaContabilId()),
                () -> assertEquals(cenario.plano().getId(), partida.getPlanoContasId()),
                () -> assertEquals(TipoPartida.DEBITO, partida.getTipo()),
                () -> assertEquals(new BigDecimal("99999999999999999.99"), partida.getValor()),
                () -> assertEquals("Compra", partida.getHistorico()),
                () -> assertEquals(1, partidas.countByLancamentoId(rascunho.getId()))
        );
    }

    @Test
    void rejeitaContaDeOutroPlanoESintetica() {
        LancamentoContabil rascunho = criarRascunho(cenario);
        Cenario outro = criarCenario();
        ContaContabil sintetica = criarConta(cenario.plano(), TipoConta.SINTETICA);
        assertThrows(IllegalArgumentException.class, () -> adicionar(cenario, rascunho, outro.conta(), VALOR));
        assertThrows(IllegalArgumentException.class, () -> adicionar(cenario, rascunho, sintetica, VALOR));
        assertFalse(partidas.existsByLancamentoId(rascunho.getId()));
    }

    @Test
    void rejeitaContaInativa() {
        LancamentoContabil rascunho = criarRascunho(cenario);
        alterarEstado("UPDATE conta_contabil SET ativa = false WHERE id = :id", cenario.conta().getId());
        assertThrows(IllegalArgumentException.class, () -> adicionar(cenario, rascunho, cenario.conta(), VALOR));
        assertFalse(partidas.existsByLancamentoId(rascunho.getId()));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"0", "-1.00", "0.001", "100000000000000000.00", "1E+10000"})
    void rejeitaValorMonetarioInvalido(String entrada) {
        LancamentoContabil rascunho = criarRascunho(cenario);
        BigDecimal valor = entrada == null ? null : new BigDecimal(entrada);
        assertThrows(IllegalArgumentException.class, () -> adicionar(cenario, rascunho, cenario.conta(), valor));
        assertFalse(partidas.existsByLancamentoId(rascunho.getId()));
    }

    @Test
    void rejeitaTipoAusenteEHistoricoEmBrancoNaPartida() {
        LancamentoContabil rascunho = criarRascunho(cenario);
        assertThrows(IllegalArgumentException.class, () -> service.adicionarPartida(
                cenario.empresa().getId(), cenario.usuario().getId(), rascunho.getId(),
                cenario.conta().getId(), null, VALOR, null
        ));
        assertThrows(IllegalArgumentException.class, () -> service.adicionarPartida(
                cenario.empresa().getId(), cenario.usuario().getId(), rascunho.getId(),
                cenario.conta().getId(), TipoPartida.DEBITO, VALOR, " "
        ));
        assertFalse(partidas.existsByLancamentoId(rascunho.getId()));
    }

    @Test
    void removePartidaDoRascunhoERestaZeroPartidas() {
        LancamentoContabil rascunho = criarRascunho(cenario);
        PartidaContabil partida = adicionar(cenario, rascunho, cenario.conta(), VALOR);
        remover(cenario, rascunho, partida);
        entityManager.flush();
        entityManager.clear();

        assertFalse(partidas.existsById(partida.getId()));
        assertEquals(0, partidas.countByLancamentoId(rascunho.getId()));
        assertEquals(StatusLancamento.RASCUNHO, lancamentos.findById(rascunho.getId()).orElseThrow().getStatus());
    }

    @Test
    void rejeitaRemocaoDePartidaDeOutroLancamento() {
        LancamentoContabil rascunho = criarRascunho(cenario);
        LancamentoContabil outro = criarRascunho(cenario);
        PartidaContabil partidaAlheia = adicionar(cenario, outro, cenario.conta(), VALOR);
        assertThrows(IllegalArgumentException.class, () -> remover(cenario, rascunho, partidaAlheia));
        assertTrue(partidas.existsById(partidaAlheia.getId()));
    }

    @Test
    void permiteAlterarPartidasDeRascunhoAposEncerramentoDoPeriodo() {
        LancamentoContabil rascunho = criarRascunho(cenario);
        alterarEstado("UPDATE periodo_contabil SET status = 'ENCERRADO' WHERE id = :id",
                cenario.periodo().getId());
        alterarEstado("UPDATE exercicio_contabil SET status = 'ENCERRADO' WHERE id = :id",
                cenario.exercicio().getId());

        PartidaContabil partida = adicionar(cenario, rascunho, cenario.conta(), VALOR);
        assertEquals(1, partidas.countByLancamentoId(rascunho.getId()));
        remover(cenario, rascunho, partida);
        assertFalse(partidas.existsByLancamentoId(rascunho.getId()));
    }

    @Test
    void rejeitaInclusaoERemocaoEmLancamentoConfirmadoOuEstornado() {
        LancamentoContabil original = criarRascunho(cenario);
        PartidaContabil debito = adicionar(cenario, original, cenario.conta(), VALOR);
        PartidaContabil credito = service.adicionarPartida(cenario.empresa().getId(), cenario.usuario().getId(),
                original.getId(), cenario.conta().getId(), TipoPartida.CREDITO, VALOR, null);
        confirmarFixture(original, 1L);
        assertThrows(IllegalStateException.class, () -> adicionar(cenario, original, cenario.conta(), VALOR));
        assertThrows(IllegalStateException.class, () -> remover(cenario, original, debito));

        LancamentoContabil estorno = lancamentos.saveAndFlush(LancamentoContabil.criarEstorno(
                original, cenario.exercicio(), cenario.periodo(), cenario.usuario(), DATA, "Estorno de teste"
        ));
        partidas.saveAllAndFlush(List.of(
                new PartidaContabil(estorno, cenario.conta(), TipoPartida.CREDITO, debito.getValor(), null),
                new PartidaContabil(estorno, cenario.conta(), TipoPartida.DEBITO, credito.getValor(), null)
        ));
        confirmarFixture(estorno, 2L);
        original.marcarComoEstornado();
        entityManager.flush();
        entityManager.clear();

        assertEquals(StatusLancamento.ESTORNADO, lancamentos.findById(original.getId()).orElseThrow().getStatus());
        assertThrows(IllegalStateException.class, () -> adicionar(cenario, original, cenario.conta(), VALOR));
        assertThrows(IllegalStateException.class, () -> remover(cenario, original, debito));
        assertEquals(2, partidas.countByLancamentoId(original.getId()));
    }

    @Test
    void falhaDeMutacaoReverteInclusiveGravacoesAnterioresDaMesmaTransacao() {
        TransactionTemplate transacao = new TransactionTemplate(transactionManager);
        transacao.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        AtomicReference<UUID> rascunhoId = new AtomicReference<>();
        AtomicReference<UUID> partidaId = new AtomicReference<>();

        assertThrows(IllegalArgumentException.class, () -> transacao.executeWithoutResult(status -> {
            Cenario independente = criarCenario();
            LancamentoContabil rascunho = criarRascunho(independente);
            PartidaContabil partida = adicionar(independente, rascunho, independente.conta(), VALOR);
            rascunhoId.set(rascunho.getId());
            partidaId.set(partida.getId());
            entityManager.flush();
            adicionar(independente, rascunho, independente.conta(), new BigDecimal("0.001"));
        }));

        assertFalse(lancamentos.existsById(rascunhoId.get()));
        assertFalse(partidas.existsById(partidaId.get()));
    }

    private Cenario criarCenario() {
        Usuario usuario = usuarios.save(new Usuario("Usuário teste", UUID.randomUUID() + "@example.test", "hash-teste"));
        Empresa empresa = empresas.save(new Empresa(usuario, "Empresa teste", "Empresa teste", null));
        PlanoContas plano = planos.save(new PlanoContas(empresa, modelos.findById(1).orElseThrow()));
        ExercicioContabil exercicio = criarExercicio(empresa, 2026);
        PeriodoContabil periodo = criarPeriodo(exercicio, 1);
        return new Cenario(usuario, empresa, plano, exercicio, periodo, criarConta(plano, TipoConta.ANALITICA));
    }

    private ExercicioContabil criarExercicio(Empresa empresa, int ano) {
        return exercicios.save(new ExercicioContabil(empresa, (short) ano,
                LocalDate.of(ano, 1, 1), LocalDate.of(ano, 12, 31)));
    }

    private PeriodoContabil criarPeriodo(ExercicioContabil exercicio, int mes) {
        YearMonth referencia = YearMonth.of(exercicio.getAno(), mes);
        return periodos.save(new PeriodoContabil(exercicio, (short) mes, referencia.atDay(1), referencia.atEndOfMonth()));
    }

    private ContaContabil criarConta(PlanoContas plano, TipoConta tipo) {
        ModeloContaContabil modelo = modelosConta.findAllByModeloVersaoOrderByCodigoAsc(plano.getModeloVersao())
                .stream()
                .filter(conta -> conta.getTipo() == tipo && Boolean.FALSE.equals(conta.getContaRedutora()))
                .findFirst().orElseThrow();
        return contas.save(new ContaContabil(plano, modelo, null, null));
    }

    private LancamentoContabil criarRascunho(Cenario dados) {
        return service.criarRascunho(dados.empresa().getId(), dados.usuario().getId(),
                dados.exercicio().getId(), dados.periodo().getId(), DATA, " Compra manual ");
    }

    private PartidaContabil adicionar(Cenario dados, LancamentoContabil rascunho, ContaContabil conta, BigDecimal valor) {
        return service.adicionarPartida(dados.empresa().getId(), dados.usuario().getId(),
                rascunho.getId(), conta.getId(), TipoPartida.DEBITO, valor, null);
    }

    private void remover(Cenario dados, LancamentoContabil rascunho, PartidaContabil partida) {
        service.removerPartida(dados.empresa().getId(), dados.usuario().getId(), rascunho.getId(), partida.getId());
    }

    private void confirmarFixture(LancamentoContabil lancamento, long numero) {
        entityManager.flush();
        lancamento.confirmar(numero, cenario.usuario(), OffsetDateTime.now());
        entityManager.flush();
    }

    private void alterarEstado(String sql, UUID id) {
        entityManager.flush();
        entityManager.createNativeQuery(sql).setParameter("id", id).executeUpdate();
        entityManager.clear();
    }

    private record Cenario(Usuario usuario, Empresa empresa, PlanoContas plano, ExercicioContabil exercicio,
                           PeriodoContabil periodo, ContaContabil conta) {
    }
}
