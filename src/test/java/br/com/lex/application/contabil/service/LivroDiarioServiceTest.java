package br.com.lex.application.contabil.service;

import br.com.lex.application.contabil.dto.LivroDiario;
import br.com.lex.domain.contabil.entity.ContaContabil;
import br.com.lex.domain.contabil.entity.Empresa;
import br.com.lex.domain.contabil.entity.ExercicioContabil;
import br.com.lex.domain.contabil.entity.ModeloContaContabil;
import br.com.lex.domain.contabil.entity.PeriodoContabil;
import br.com.lex.domain.contabil.entity.PlanoContas;
import br.com.lex.domain.contabil.entity.Usuario;
import br.com.lex.domain.contabil.enums.OrigemLancamento;
import br.com.lex.domain.contabil.enums.StatusLancamento;
import br.com.lex.domain.contabil.enums.TipoConta;
import br.com.lex.domain.contabil.enums.TipoPartida;
import br.com.lex.domain.contabil.repository.ModeloContaContabilRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class LivroDiarioServiceTest {

    private static final LocalDate DATA = LocalDate.of(2026, 1, 15);
    private static final BigDecimal VALOR = new BigDecimal("125.40");

    @Autowired private LivroDiarioService diario;
    @Autowired private LancamentoContabilService lancamentos;
    @Autowired private ModeloContaContabilRepository modelosContas;
    @Autowired private EntityManager em;
    @Autowired private JdbcTemplate jdbc;

    private Cenario dados;

    @BeforeEach
    void preparar() {
        dados = criarCenario();
    }

    @Test
    void emitePorDataENumeroComPartidasIndividuaisTotaisECorteInclusivo() {
        UUID posterior = confirmar(dados, DATA.plusDays(1));
        UUID primeiro = confirmar(dados, DATA);
        UUID segundo = confirmar(dados, DATA);
        confirmar(dados, DATA.minusDays(1));
        confirmar(dados, DATA.plusDays(2));
        UUID rascunho = criarRascunho(dados, DATA);
        lancamentos.adicionarPartida(dados.empresa(), dados.usuario(), rascunho, dados.contaDebito().getId(),
                TipoPartida.DEBITO, VALOR, null);

        LivroDiario resultado = emitir(DATA, DATA.plusDays(1));

        assertThat(resultado.empresa().id()).isEqualTo(dados.empresa());
        assertThat(resultado.empresa().razaoSocial()).isEqualTo("Empresa de teste");
        assertThat(resultado.exercicio().id()).isEqualTo(dados.exercicio());
        assertThat(resultado.exercicio().ano()).isEqualTo((short) 2026);
        assertThat(resultado.inicio()).isEqualTo(DATA);
        assertThat(resultado.fim()).isEqualTo(DATA.plusDays(1));
        assertThat(resultado.lancamentos()).extracting(l -> l.referencia().id())
                .containsExactly(primeiro, segundo, posterior);
        assertThat(resultado.lancamentos()).extracting(l -> l.referencia().numero())
                .containsExactly(2L, 3L, 1L);
        assertThat(resultado.totalDebitos()).isEqualByComparingTo("376.20");
        assertThat(resultado.totalCreditos()).isEqualByComparingTo("376.20");

        LivroDiario.Lancamento item = resultado.lancamentos().getFirst();
        assertThat(item.data()).isEqualTo(DATA);
        assertThat(item.periodoId()).isEqualTo(dados.periodo());
        assertThat(item.historico()).isEqualTo("Histórico do lançamento");
        assertThat(item.origem()).isEqualTo(OrigemLancamento.MANUAL);
        assertThat(item.status()).isEqualTo(StatusLancamento.CONFIRMADO);
        assertThat(item.original()).isNull();
        assertThat(item.totalDebitos()).isEqualByComparingTo(VALOR);
        assertThat(item.totalCreditos()).isEqualByComparingTo(VALOR);
        assertThat(item.partidas()).hasSize(3).extracting(LivroDiario.Partida::id).doesNotHaveDuplicates();
        assertThat(item.partidas()).filteredOn(p -> p.tipo() == TipoPartida.DEBITO)
                .hasSize(2).allSatisfy(p -> {
                    assertThat(p.contaId()).isEqualTo(dados.contaDebito().getId());
                    assertThat(p.codigoConta()).isEqualTo(dados.contaDebito().getCodigo());
                    assertThat(p.nomeConta()).isEqualTo(dados.contaDebito().getNome());
                    assertThat(p.valor()).isEqualByComparingTo("62.70");
                    assertThat(p.historico()).isEqualTo("Histórico da partida");
                });
        assertThat(item.partidas()).filteredOn(p -> p.tipo() == TipoPartida.CREDITO)
                .singleElement().satisfies(p -> {
                    assertThat(p.valor()).isEqualByComparingTo(VALOR);
                    assertThat(p.historico()).isNull();
                });
    }

    @Test
    void preservaOriginalEEstornoEmSeusPeriodosMesmoAposEncerramentoEInativacao() {
        UUID original = confirmar(dados, DATA);
        LocalDate dataEstorno = DATA.plusMonths(1);
        UUID fevereiro = criarPeriodo(em.find(ExercicioContabil.class, dados.exercicio()), YearMonth.from(dataEstorno));
        em.flush();
        UUID estorno = lancamentos.criarRascunhoEstorno(dados.empresa(), dados.usuario(), original,
                dados.exercicio(), fevereiro, dataEstorno, "Estorno posterior").getId();

        assertThat(emitir(dataEstorno, dataEstorno).lancamentos()).isEmpty();
        assertThat(emitir(DATA, DATA).lancamentos().getFirst().status()).isEqualTo(StatusLancamento.CONFIRMADO);

        lancamentos.confirmarEstorno(dados.empresa(), dados.usuario(), estorno);
        jdbc.update("update periodo_contabil set status = 'ENCERRADO' where exercicio_id = ?", dados.exercicio());
        jdbc.update("update exercicio_contabil set status = 'ENCERRADO' where id = ?", dados.exercicio());
        jdbc.update("update conta_contabil set ativa = false where id = ?", dados.contaDebito().getId());
        em.clear();

        LivroDiario janeiro = emitir(DATA, DATA);
        LivroDiario fevereiroDiario = emitir(dataEstorno, dataEstorno);

        assertThat(janeiro.lancamentos()).singleElement().satisfies(l -> {
            assertThat(l.referencia().id()).isEqualTo(original);
            assertThat(l.status()).isEqualTo(StatusLancamento.ESTORNADO);
            assertThat(l.partidas()).filteredOn(p -> p.contaId().equals(dados.contaDebito().getId()))
                    .hasSize(2).allSatisfy(p -> assertThat(p.tipo()).isEqualTo(TipoPartida.DEBITO));
        });
        assertThat(fevereiroDiario.lancamentos()).singleElement().satisfies(l -> {
            assertThat(l.referencia().id()).isEqualTo(estorno);
            assertThat(l.periodoId()).isEqualTo(fevereiro);
            assertThat(l.data()).isEqualTo(dataEstorno);
            assertThat(l.origem()).isEqualTo(OrigemLancamento.ESTORNO);
            assertThat(l.status()).isEqualTo(StatusLancamento.CONFIRMADO);
            assertThat(l.original()).isEqualTo(new LivroDiario.ReferenciaLancamento(original, dados.exercicio(), 1L));
            assertThat(l.partidas()).filteredOn(p -> p.contaId().equals(dados.contaDebito().getId()))
                    .hasSize(2).allSatisfy(p -> assertThat(p.tipo()).isEqualTo(TipoPartida.CREDITO));
        });
        assertThat(janeiro.totalDebitos()).isEqualByComparingTo(VALOR);
        assertThat(janeiro.totalCreditos()).isEqualByComparingTo(VALOR);
        assertThat(fevereiroDiario.totalDebitos()).isEqualByComparingTo(VALOR);
        assertThat(fevereiroDiario.totalCreditos()).isEqualByComparingTo(VALOR);
        assertThat(emitir(DATA, dataEstorno).totalDebitos()).isEqualByComparingTo("250.80");
        assertThat(emitir(DATA, dataEstorno).totalCreditos()).isEqualByComparingTo("250.80");
    }

    @Test
    void isolaEmpresasERetornaTotaisZeradosQuandoSoHaMovimentoEmOutraEmpresa() {
        Cenario outra = criarCenario();
        confirmar(outra, DATA);

        LivroDiario vazio = emitir(DATA, DATA);
        assertThat(vazio.empresa().id()).isEqualTo(dados.empresa());
        assertThat(vazio.lancamentos()).isEmpty();
        assertThat(vazio.totalDebitos()).isEqualByComparingTo("0.00");
        assertThat(vazio.totalCreditos()).isEqualByComparingTo("0.00");

        UUID proprio = confirmar(dados, DATA);
        assertThat(emitir(DATA, DATA).lancamentos()).extracting(l -> l.referencia().id()).containsExactly(proprio);
        assertThrows(IllegalArgumentException.class,
                () -> diario.emitir(dados.empresa(), dados.usuario(), outra.exercicio(), DATA, DATA));
        assertThrows(IllegalArgumentException.class,
                () -> diario.emitir(dados.empresa(), outra.usuario(), dados.exercicio(), DATA, DATA));
    }

    @Test
    void rejeitaTitularInativo() {
        em.flush();
        jdbc.update("update usuario set ativo = false where id = ?", dados.usuario());
        em.clear();

        assertThrows(IllegalStateException.class, () -> emitir(DATA, DATA));
    }

    @Test
    void rejeitaIntervaloNuloInvertidoOuForaDoExercicio() {
        assertThrows(IllegalArgumentException.class, () -> emitir(null, DATA));
        assertThrows(IllegalArgumentException.class, () -> emitir(DATA, null));
        assertThrows(IllegalArgumentException.class, () -> emitir(DATA, DATA.minusDays(1)));
        assertThrows(IllegalArgumentException.class, () -> emitir(DATA.minusYears(1), DATA));
        assertThrows(IllegalArgumentException.class, () -> emitir(DATA, DATA.plusYears(1)));
    }

    private LivroDiario emitir(LocalDate inicio, LocalDate fim) {
        return diario.emitir(dados.empresa(), dados.usuario(), dados.exercicio(), inicio, fim);
    }

    private UUID criarRascunho(Cenario cenario, LocalDate data) {
        return lancamentos.criarRascunho(cenario.empresa(), cenario.usuario(), cenario.exercicio(),
                cenario.periodo(), data, "Histórico do lançamento").getId();
    }

    private UUID confirmar(Cenario cenario, LocalDate data) {
        UUID id = criarRascunho(cenario, data);
        BigDecimal metade = new BigDecimal("62.70");
        for (int i = 0; i < 2; i++) {
            lancamentos.adicionarPartida(cenario.empresa(), cenario.usuario(), id, cenario.contaDebito().getId(),
                    TipoPartida.DEBITO, metade, "Histórico da partida");
        }
        lancamentos.adicionarPartida(cenario.empresa(), cenario.usuario(), id, cenario.contaCredito().getId(),
                TipoPartida.CREDITO, VALOR, null);
        return lancamentos.confirmarLancamento(cenario.empresa(), cenario.usuario(), id).getId();
    }

    private Cenario criarCenario() {
        Usuario usuario = new Usuario("Usuário teste", UUID.randomUUID() + "@example.test", "hash-teste");
        em.persist(usuario);
        Empresa empresa = new Empresa(usuario, "Empresa de teste", "Empresa de teste", null);
        em.persist(empresa);
        ExercicioContabil exercicio = new ExercicioContabil(empresa, (short) DATA.getYear(),
                DATA.withDayOfYear(1), LocalDate.of(DATA.getYear(), 12, 31));
        em.persist(exercicio);
        UUID periodo = criarPeriodo(exercicio, YearMonth.from(DATA));

        List<ModeloContaContabil> modelos = modelosContas.findAllByModeloVersaoOrderByCodigoAsc(1).stream()
                .filter(m -> m.getTipo() == TipoConta.ANALITICA && !m.getContaRedutora())
                .limit(2).toList();
        PlanoContas plano = new PlanoContas(empresa, modelos.getFirst().getModeloPlanoContas());
        em.persist(plano);
        ContaContabil debito = new ContaContabil(plano, modelos.get(0), null, null);
        ContaContabil credito = new ContaContabil(plano, modelos.get(1), null, null);
        em.persist(debito);
        em.persist(credito);
        em.flush();
        return new Cenario(empresa.getId(), usuario.getId(), exercicio.getId(), periodo, debito, credito);
    }

    private UUID criarPeriodo(ExercicioContabil exercicio, YearMonth mes) {
        PeriodoContabil periodo = new PeriodoContabil(exercicio, (short) mes.getMonthValue(),
                mes.atDay(1), mes.atEndOfMonth());
        em.persist(periodo);
        return periodo.getId();
    }

    private record Cenario(UUID empresa, UUID usuario, UUID exercicio, UUID periodo,
                           ContaContabil contaDebito, ContaContabil contaCredito) {
    }
}
