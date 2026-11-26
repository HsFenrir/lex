package br.com.lex.application.contabil.service;

import br.com.lex.application.contabil.dto.LivroDiario;
import br.com.lex.application.contabil.dto.LivroRazao;
import br.com.lex.domain.contabil.entity.ContaContabil;
import br.com.lex.domain.contabil.entity.Empresa;
import br.com.lex.domain.contabil.entity.ExercicioContabil;
import br.com.lex.domain.contabil.entity.LancamentoContabil;
import br.com.lex.domain.contabil.entity.ModeloContaContabil;
import br.com.lex.domain.contabil.entity.PeriodoContabil;
import br.com.lex.domain.contabil.entity.PlanoContas;
import br.com.lex.domain.contabil.entity.Usuario;
import br.com.lex.domain.contabil.enums.NaturezaConta;
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

import static br.com.lex.domain.contabil.ApuracaoSaldos.NaturezaSaldo.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class LivroRazaoServiceTest {

    private static final LocalDate DATA = LocalDate.of(2026, 1, 15);

    @Autowired private LivroRazaoService razao;
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
    void apuraAberturaMovimentosAcumuladosETotaisComReconciliacaoAoDiario() {
        confirmar(dados, DATA.minusDays(1), OrigemLancamento.ABERTURA, TipoPartida.DEBITO, "100.00");
        UUID posterior = confirmar(dados, DATA.plusDays(1), OrigemLancamento.MANUAL, TipoPartida.CREDITO, "130.00");
        UUID primeiro = confirmar(dados, DATA, OrigemLancamento.MANUAL, TipoPartida.DEBITO, "10.00", "10.00");
        UUID segundo = confirmar(dados, DATA, OrigemLancamento.MANUAL, TipoPartida.CREDITO, "5.00");
        confirmar(dados, DATA.plusDays(2), OrigemLancamento.MANUAL, TipoPartida.DEBITO, "999.00");
        UUID rascunho = rascunho(dados, DATA, OrigemLancamento.MANUAL);
        lancamentos.adicionarPartida(dados.empresa(), dados.usuario(), rascunho, dados.conta().getId(),
                TipoPartida.DEBITO, BigDecimal.ONE, "Rascunho");

        LivroRazao resultado = emitir(dados.conta().getId(), DATA, DATA.plusDays(1));
        assertThat(resultado.conta()).isEqualTo(new LivroRazao.Conta(dados.conta().getId(),
                dados.conta().getCodigo(), dados.conta().getNome(), NaturezaConta.DEVEDORA));
        assertThat(resultado.movimentos()).extracting(LivroRazao.Movimento::lancamentoId)
                .containsExactly(primeiro, primeiro, segundo, posterior);
        assertThat(resultado.movimentos()).extracting(m -> m.saldoAcumulado().valor())
                .containsExactly(new BigDecimal("110.00"), new BigDecimal("120.00"),
                        new BigDecimal("115.00"), new BigDecimal("-15.00"));
        assertThat(resultado.saldos().saldoAnterior().valor()).isEqualByComparingTo("100.00");
        assertThat(resultado.saldos().saldoAnterior().natureza()).isEqualTo(DEVEDOR);
        assertThat(resultado.saldos().totalDebitos()).isEqualByComparingTo("20.00");
        assertThat(resultado.saldos().totalCreditos()).isEqualByComparingTo("135.00");
        assertThat(resultado.saldos().saldoFinal().valor()).isEqualByComparingTo("-15.00");
        assertThat(resultado.saldos().saldoFinal().natureza()).isEqualTo(CREDOR);

        LivroDiario diarioEmitido = diario.emitir(dados.empresa(), dados.usuario(), dados.exercicio(), DATA, DATA.plusDays(1));
        List<Linha> linhasDiario = diarioEmitido.lancamentos().stream()
                .flatMap(l -> l.partidas().stream().filter(p -> p.contaId().equals(dados.conta().getId()))
                        .map(p -> new Linha(l.data(), l.referencia().id(), l.referencia().numero(), l.historico(),
                                p.id(), p.historico(), p.tipo(), p.valor())))
                .toList();
        assertThat(resultado.movimentos().stream().map(m -> new Linha(m.data(), m.lancamentoId(),
                m.numeroLancamento(), m.historicoLancamento(), m.partidaId(), m.historicoPartida(),
                m.tipo(), m.valor())).toList()).containsExactlyElementsOf(linhasDiario);
    }

    @Test
    void distingueContaSemMovimentoNoExercicioDeRecorteComSaldoAnterior() {
        LivroRazao vazio = emitir(dados.conta().getId(), DATA, DATA);
        assertThat(vazio.movimentos()).isEmpty();
        assertThat(vazio.saldos().saldoAnterior().natureza()).isEqualTo(ZERADO);
        assertThat(vazio.saldos().saldoFinal().valor()).isEqualByComparingTo("0.00");
        assertThat(vazio.saldos().saldoFinal().natureza()).isEqualTo(ZERADO);

        confirmar(dados, DATA.minusDays(1), OrigemLancamento.MANUAL, TipoPartida.DEBITO, "30.00");
        LivroRazao anterior = emitir(dados.conta().getId(), DATA, DATA);
        assertThat(anterior.movimentos()).isEmpty();
        assertThat(anterior.saldos().saldoAnterior().valor()).isEqualByComparingTo("30.00");
        assertThat(anterior.saldos().saldoFinal()).isEqualTo(anterior.saldos().saldoAnterior());
        assertThat(anterior.saldos().totalDebitos()).isEqualByComparingTo("0.00");
        assertThat(anterior.saldos().totalCreditos()).isEqualByComparingTo("0.00");
    }

    @Test
    void incluiOriginalEEstornoEmDatasDistintasMesmoComContaInativaEExercicioEncerrado() {
        UUID original = confirmar(dados, DATA, OrigemLancamento.MANUAL, TipoPartida.DEBITO, "25.00");
        LocalDate dataEstorno = DATA.plusMonths(1);
        UUID fevereiro = criarPeriodo(em.find(ExercicioContabil.class, dados.exercicio()), YearMonth.from(dataEstorno));
        em.flush();
        UUID estorno = lancamentos.criarRascunhoEstorno(dados.empresa(), dados.usuario(), original,
                dados.exercicio(), fevereiro, dataEstorno, "Estorno").getId();
        lancamentos.confirmarEstorno(dados.empresa(), dados.usuario(), estorno);

        jdbc.update("update conta_contabil set ativa = false where id = ?", dados.conta().getId());
        jdbc.update("update periodo_contabil set status = 'ENCERRADO' where exercicio_id = ?", dados.exercicio());
        jdbc.update("update exercicio_contabil set status = 'ENCERRADO' where id = ?", dados.exercicio());
        em.clear();

        assertThat(emitir(dados.conta().getId(), DATA, DATA).movimentos()).singleElement().satisfies(m -> {
            assertThat(m.lancamentoId()).isEqualTo(original);
            assertThat(m.status()).isEqualTo(StatusLancamento.ESTORNADO);
            assertThat(m.tipo()).isEqualTo(TipoPartida.DEBITO);
        });
        LivroRazao recorte = emitir(dados.conta().getId(), dataEstorno, dataEstorno);
        assertThat(recorte.movimentos()).singleElement().satisfies(m -> {
            assertThat(m.lancamentoId()).isEqualTo(estorno);
            assertThat(m.status()).isEqualTo(StatusLancamento.CONFIRMADO);
            assertThat(m.origem()).isEqualTo(OrigemLancamento.ESTORNO);
            assertThat(m.tipo()).isEqualTo(TipoPartida.CREDITO);
            assertThat(m.saldoAcumulado().natureza()).isEqualTo(ZERADO);
        });
        assertThat(recorte.saldos().saldoAnterior().valor()).isEqualByComparingTo("25.00");
        assertThat(recorte.saldos().saldoFinal().valor()).isEqualByComparingTo("0.00");
    }

    @Test
    void acumulaValoresAcimaDaPrecisaoDeUmaPartida() {
        String limite = "99999999999999999.99";
        confirmar(dados, DATA, OrigemLancamento.MANUAL, TipoPartida.DEBITO, limite, limite);
        LivroRazao resultado = emitir(dados.conta().getId(), DATA, DATA);
        assertThat(resultado.saldos().totalDebitos()).isEqualByComparingTo("199999999999999999.98");
        assertThat(resultado.saldos().saldoFinal().valor()).isEqualByComparingTo("199999999999999999.98");
    }

    @Test
    void isolaEmpresasERejeitaContextosIncompativeisEContaSintetica() {
        Cenario outra = criarCenario();
        confirmar(outra, DATA, OrigemLancamento.MANUAL, TipoPartida.DEBITO, "45.00");
        assertThat(emitir(dados.conta().getId(), DATA, DATA).movimentos()).isEmpty();
        assertThrows(IllegalArgumentException.class, () -> emitir(outra.conta().getId(), DATA, DATA));
        assertThrows(IllegalArgumentException.class, () -> razao.emitir(dados.empresa(), outra.usuario(),
                dados.exercicio(), dados.conta().getId(), DATA, DATA));
        assertThrows(IllegalArgumentException.class, () -> razao.emitir(dados.empresa(), dados.usuario(),
                outra.exercicio(), dados.conta().getId(), DATA, DATA));
        assertThrows(IllegalArgumentException.class, () -> emitir(dados.sintetica(), DATA, DATA));
        assertThrows(IllegalArgumentException.class, () -> emitir(null, DATA, DATA));
        assertThrows(IllegalArgumentException.class, () -> emitir(dados.conta().getId(), DATA, DATA.minusDays(1)));
        assertThrows(IllegalArgumentException.class, () -> emitir(dados.conta().getId(), DATA.minusYears(1), DATA));

        jdbc.update("update usuario set ativo = false where id = ?", dados.usuario());
        em.clear();
        assertThrows(IllegalStateException.class, () -> emitir(dados.conta().getId(), DATA, DATA));
    }

    private LivroRazao emitir(UUID contaId, LocalDate inicio, LocalDate fim) {
        return razao.emitir(dados.empresa(), dados.usuario(), dados.exercicio(), contaId, inicio, fim);
    }

    private UUID confirmar(Cenario cenario, LocalDate data, OrigemLancamento origem, TipoPartida tipo, String... valores) {
        UUID id = rascunho(cenario, data, origem);
        TipoPartida contrapartida = tipo == TipoPartida.DEBITO ? TipoPartida.CREDITO : TipoPartida.DEBITO;
        for (String valor : valores) {
            BigDecimal quantia = new BigDecimal(valor);
            lancamentos.adicionarPartida(cenario.empresa(), cenario.usuario(), id, cenario.conta().getId(),
                    tipo, quantia, "Histórico da partida");
            lancamentos.adicionarPartida(cenario.empresa(), cenario.usuario(), id, cenario.contrapartida(),
                    contrapartida, quantia, null);
        }
        return lancamentos.confirmarLancamento(cenario.empresa(), cenario.usuario(), id).getId();
    }

    private UUID rascunho(Cenario cenario, LocalDate data, OrigemLancamento origem) {
        LancamentoContabil lancamento = new LancamentoContabil(
                em.getReference(Empresa.class, cenario.empresa()),
                em.getReference(ExercicioContabil.class, cenario.exercicio()),
                em.getReference(PeriodoContabil.class, cenario.periodo()),
                em.getReference(PlanoContas.class, cenario.plano()),
                em.getReference(Usuario.class, cenario.usuario()), data, "Histórico do lançamento", origem);
        em.persist(lancamento);
        em.flush();
        return lancamento.getId();
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

        List<ModeloContaContabil> modelos = modelosContas.findAllByModeloVersaoOrderByCodigoAsc(1);
        List<ModeloContaContabil> analiticas = modelos.stream()
                .filter(m -> m.getTipo() == TipoConta.ANALITICA && m.getNatureza() == NaturezaConta.DEVEDORA
                        && !m.getContaRedutora())
                .limit(2).toList();
        ModeloContaContabil sintetica = modelos.stream().filter(m -> m.getTipo() == TipoConta.SINTETICA)
                .findFirst().orElseThrow();
        PlanoContas plano = new PlanoContas(empresa, sintetica.getModeloPlanoContas());
        em.persist(plano);
        ContaContabil conta = persistirConta(plano, analiticas.get(0));
        ContaContabil contrapartida = persistirConta(plano, analiticas.get(1));
        ContaContabil contaSintetica = persistirConta(plano, sintetica);
        em.flush();
        return new Cenario(empresa.getId(), usuario.getId(), exercicio.getId(), periodo, plano.getId(),
                conta, contrapartida.getId(), contaSintetica.getId());
    }

    private ContaContabil persistirConta(PlanoContas plano, ModeloContaContabil modelo) {
        ContaContabil conta = new ContaContabil(plano, modelo, null, null);
        em.persist(conta);
        return conta;
    }

    private UUID criarPeriodo(ExercicioContabil exercicio, YearMonth mes) {
        PeriodoContabil periodo = new PeriodoContabil(exercicio, (short) mes.getMonthValue(),
                mes.atDay(1), mes.atEndOfMonth());
        em.persist(periodo);
        return periodo.getId();
    }

    private record Cenario(UUID empresa, UUID usuario, UUID exercicio, UUID periodo, UUID plano,
                           ContaContabil conta, UUID contrapartida, UUID sintetica) {
    }

    private record Linha(LocalDate data, UUID lancamento, long numero, String historicoLancamento,
                         UUID partida, String historicoPartida, TipoPartida tipo, BigDecimal valor) {
    }
}
