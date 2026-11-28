package br.com.lex.application.contabil.service;

import br.com.lex.application.contabil.dto.AberturaContabil.SaldoPorConta;
import br.com.lex.application.contabil.dto.OperacaoEstoque;
import br.com.lex.application.contabil.dto.OperacaoEstoqueConfirmada;
import br.com.lex.application.contabil.dto.SaldoInicialEstoque;
import br.com.lex.application.contabil.dto.ReversaoOperacional;
import br.com.lex.domain.contabil.entity.Empresa;
import br.com.lex.domain.contabil.entity.ExercicioContabil;
import br.com.lex.domain.contabil.entity.LancamentoContabil;
import br.com.lex.domain.contabil.entity.PartidaContabil;
import br.com.lex.domain.contabil.entity.PeriodoContabil;
import br.com.lex.domain.contabil.entity.ProdutoEstoque;
import br.com.lex.domain.contabil.entity.Usuario;
import br.com.lex.domain.contabil.enums.OrigemLancamento;
import br.com.lex.domain.contabil.enums.TipoPartida;
import br.com.lex.domain.contabil.repository.ContaContabilRepository;
import br.com.lex.domain.contabil.repository.MovimentoEstoqueRepository;
import br.com.lex.domain.contabil.repository.MovimentoEstoqueRepository.Alocacao;
import br.com.lex.domain.contabil.repository.MovimentoEstoqueRepository.Lote;
import br.com.lex.domain.contabil.repository.MovimentoEstoqueRepository.Movimento;
import br.com.lex.domain.contabil.repository.MovimentoEstoqueRepository.Saldo;
import br.com.lex.domain.contabil.repository.MovimentoEstoqueRepository.Tipo;
import br.com.lex.domain.contabil.repository.ProdutoEstoqueRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class EstoqueService {

    // Códigos de referência do modelo padrão versão 1, sem depender do código no plano da empresa.
    static final String MERCADORIAS = "1.1.03.001";
    private static final String RECEITA = "4.1.001";
    private static final String CMV = "5.1.001";
    private static final String CAIXA = "1.1.01.001";
    private static final String BANCOS = "1.1.01.002";
    private static final List<String> CONTRAPARTIDAS_COMPRA = List.of(CAIXA, BANCOS, "2.1.01.001", "2.2.02.001");
    private static final List<String> CONTRAPARTIDAS_VENDA = List.of(CAIXA, BANCOS, "1.1.02.001");

    private final ProdutoEstoqueRepository produtos;
    private final MovimentoEstoqueRepository movimentos;
    private final ContaContabilRepository contas;
    private final LancamentoContabilService contabilidade;
    private final EntityManager em;
    private final JdbcTemplate jdbc;

    @Transactional
    public Produto cadastrarProduto(UUID empresaId, UUID usuarioId, String codigo, String descricao, String unidade) {
        contabilidade.validarAcessoOperacional(empresaId, usuarioId);
        String referencia = textoObrigatorio(codigo, 50, "Código", true);
        ProdutoEstoque produto = produtos.saveAndFlush(new ProdutoEstoque(empresaId, referencia,
                textoObrigatorio(descricao, 200, "Descrição", false),
                textoObrigatorio(unidade, 16, "Unidade", true)));
        return dados(produto);
    }

    @Transactional
    public Produto alterarAtividade(UUID empresaId, UUID usuarioId, UUID produtoId, boolean ativo) {
        contabilidade.validarAcessoOperacional(empresaId, usuarioId);
        ProdutoEstoque produto = bloquearProduto(empresaId, produtoId);
        produto.alterarAtividade(ativo);
        return dados(produto);
    }

    @Transactional(readOnly = true)
    public List<Produto> listarProdutos(UUID empresaId, UUID usuarioId) {
        contabilidade.validarAcessoOperacional(empresaId, usuarioId);
        return produtos.findAllByEmpresaIdOrderByCodigoAsc(empresaId).stream().map(this::dados).toList();
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Ficha consultar(UUID empresaId, UUID usuarioId, UUID produtoId) {
        contabilidade.validarAcessoOperacional(empresaId, usuarioId);
        ProdutoEstoque produto = buscarProduto(empresaId, produtoId);
        return new Ficha(dados(produto), movimentos.saldo(empresaId, produtoId),
                movimentos.lotes(empresaId, produtoId, true), movimentos.historico(empresaId, produtoId),
                movimentos.historicoEstornos(empresaId, produtoId));
    }

    @Transactional(readOnly = true)
    public List<Alocacao> consultarAlocacoes(UUID empresaId, UUID usuarioId, UUID produtoId, UUID saidaId) {
        contabilidade.validarAcessoOperacional(empresaId, usuarioId);
        buscarProduto(empresaId, produtoId);
        return List.copyOf(movimentos.alocacoes(empresaId, produtoId, saidaId));
    }

    /** Vincula um inventário integral à abertura existente, na própria data da migração. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<Movimento> importarSaldoInicial(SaldoInicialEstoque saldo) {
        if (saldo == null || saldo.lotes().isEmpty() || saldo.lotes().stream().anyMatch(lote -> lote.produtoId() == null)) {
            throw new IllegalArgumentException("Informe ao menos um lote com produto para a importação.");
        }
        validarIsolamento();
        UUID empresaId = saldo.empresaId();
        LancamentoContabil abertura = contabilidade.bloquearAberturaParaImportacao(
                empresaId, saldo.usuarioId(), saldo.aberturaId(), LockModeType.PESSIMISTIC_READ);
        if (movimentos.existeVinculado(empresaId, abertura.getId())) {
            throw new IllegalStateException("A abertura já possui lotes iniciais importados.");
        }
        UUID mercadorias = resolverConta(empresaId, MERCADORIAS);
        BigDecimal saldoContabil = contabilidade.saldosDaAbertura(abertura).getOrDefault(mercadorias, BigDecimal.ZERO);

        var lotes = saldo.lotes().stream().map(lote -> {
            var contexto = new Contexto(empresaId, saldo.usuarioId(), abertura.getExercicioId(),
                    abertura.getPeriodoId(), lote.produtoId(), abertura.getData());
            return prepararEntrada(contexto, lote.quantidade(), lote.custoUnitario());
        }).toList();
        BigDecimal total = lotes.stream().map(lote -> lote.movimento().valorTotal())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.compareTo(saldoContabil) != 0) {
            throw new IllegalArgumentException("O custo total dos lotes deve corresponder ao débito de Mercadorias na abertura.");
        }

        for (UUID produtoId : saldo.lotes().stream().map(SaldoInicialEstoque.Lote::produtoId).distinct().sorted().toList()) {
            validarProdutoAtivo(bloquearProduto(empresaId, produtoId));
            if (movimentos.ultimaData(empresaId, produtoId) != null) {
                throw new IllegalStateException("Importe o saldo inicial antes da primeira movimentação de cada produto.");
            }
        }

        var importados = new ArrayList<Movimento>();
        for (Entrada lote : lotes) {
            Movimento movimento = gravarEntrada(lote);
            vincularLancamento(movimento, abertura.getId());
            auditarMovimento(movimento, abertura, "IMPORTAR_SALDO_INICIAL", null);
            importados.add(new Movimento(movimento.id(), empresaId, movimento.produtoId(), movimento.exercicioId(),
                    movimento.periodoId(), movimento.usuarioId(), movimento.data(), movimento.tipo(),
                    movimento.quantidade(), movimento.valorTotal(), abertura.getId()));
        }
        return List.copyOf(importados);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OperacaoEstoqueConfirmada registrarCompra(OperacaoEstoque operacao) {
        Movimento entrada = registrarEntrada(contexto(operacao), operacao.quantidade(), operacao.valorUnitario());
        BigDecimal valor = PartidaContabil.normalizarValor(entrada.valorTotal());
        UUID contrapartida = validarContrapartida(operacao, CONTRAPARTIDAS_COMPRA);
        UUID mercadorias = resolverConta(operacao.empresaId(), MERCADORIAS);
        return confirmarOperacao(operacao, entrada, valor, "COMPRA", List.of(
                new SaldoPorConta(mercadorias, TipoPartida.DEBITO, valor),
                new SaldoPorConta(contrapartida, TipoPartida.CREDITO, valor)));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OperacaoEstoqueConfirmada registrarVenda(OperacaoEstoque operacao) {
        Contexto contexto = contexto(operacao);
        BigDecimal quantidade = positivo(operacao.quantidade(), 19, 6, "Quantidade");
        BigDecimal preco = positivo(operacao.valorUnitario(), 19, 8, "Preço unitário");
        BigDecimal valor = PartidaContabil.normalizarValor(quantidade.multiply(preco).setScale(2, RoundingMode.HALF_UP));
        Movimento saida = registrarSaida(contexto, quantidade).movimento();
        UUID contrapartida = validarContrapartida(operacao, CONTRAPARTIDAS_VENDA);
        List<SaldoPorConta> partidas = new ArrayList<>();
        partidas.add(new SaldoPorConta(contrapartida, TipoPartida.DEBITO, valor));
        partidas.add(new SaldoPorConta(resolverConta(operacao.empresaId(), RECEITA), TipoPartida.CREDITO, valor));
        if (saida.valorTotal().signum() > 0) {
            LancamentoContabilService.adicionarSaldoEmPartidas(partidas, resolverConta(operacao.empresaId(), CMV),
                    TipoPartida.DEBITO, saida.valorTotal());
            LancamentoContabilService.adicionarSaldoEmPartidas(partidas, resolverConta(operacao.empresaId(), MERCADORIAS),
                    TipoPartida.CREDITO, saida.valorTotal());
        }
        return confirmarOperacao(operacao, saida, valor, "VENDA", partidas);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ReversaoOperacional estornarMovimento(UUID empresaId, UUID usuarioId, UUID movimentoId,
                                                 UUID periodoDestinoId, LocalDate data, String historico) {
        validarIsolamento();
        contabilidade.validarAcessoOperacional(empresaId, usuarioId);
        Movimento movimento = movimentos.buscar(empresaId, movimentoId)
                .orElseThrow(() -> new IllegalArgumentException("Movimento não encontrado na empresa."));
        var estorno = contabilidade.prepararEstornoOperacional(empresaId, usuarioId, movimento.lancamentoId(),
                OrigemLancamento.ESTOQUE, periodoDestinoId, data);
        bloquearProduto(empresaId, movimento.produtoId());
        if (movimentos.possuiOutroMovimentoVinculado(movimento)) {
            throw new IllegalStateException("O lançamento original está vinculado a mais de uma movimentação.");
        }
        if (movimentos.ultimoMovimentoEfetivo(empresaId, movimento.produtoId()).filter(movimentoId::equals).isEmpty()) {
            throw new IllegalStateException("Somente a última movimentação efetiva do produto pode ser estornada.");
        }
        if (data.isBefore(movimentos.ultimaData(empresaId, movimento.produtoId()))) {
            throw new IllegalStateException("A reversão não pode anteceder o último evento de estoque do produto.");
        }
        if (movimento.tipo() == Tipo.ENTRADA) {
            movimentos.neutralizarLote(movimento);
        } else {
            restaurarSaida(movimento);
        }
        LancamentoContabil lancamento = contabilidade.escriturarEstornoOperacional(estorno, historico);
        return contabilidade.auditarEstornoOperacional(empresaId, usuarioId, "movimento_estoque",
                movimentoId, data, lancamento, historico);
    }

    private void restaurarSaida(Movimento movimento) {
        List<Alocacao> alocacoes = movimentos.alocacoes(movimento.empresaId(), movimento.produtoId(), movimento.id());
        BigDecimal quantidade = BigDecimal.ZERO;
        BigDecimal valor = BigDecimal.ZERO;
        for (Alocacao alocacao : alocacoes) {
            quantidade = quantidade.add(alocacao.quantidade());
            valor = valor.add(alocacao.valor());
            movimentos.restaurar(movimento, alocacao);
        }
        if (quantidade.compareTo(movimento.quantidade()) != 0 || valor.compareTo(movimento.valorTotal()) != 0) {
            throw new IllegalStateException("As alocações não correspondem às quantidades e custos originais da saída.");
        }
    }

    private Contexto contexto(OperacaoEstoque operacao) {
        if (operacao == null) {
            throw new IllegalArgumentException("Informe os dados da operação de estoque.");
        }
        return new Contexto(operacao.empresaId(), operacao.usuarioId(), operacao.exercicioId(),
                operacao.periodoId(), operacao.produtoId(), operacao.data());
    }

    private UUID resolverConta(UUID empresaId, String referencia) {
        return contas.buscarContaDoModeloPadrao(empresaId, referencia)
                .orElseThrow(() -> new IllegalStateException("Conta do modelo padrão ausente no plano: " + referencia));
    }

    private UUID validarContrapartida(OperacaoEstoque operacao, List<String> referencias) {
        UUID conta = operacao.contrapartidaId();
        if (conta == null || referencias.stream()
                .map(referencia -> contas.buscarContaDoModeloPadrao(operacao.empresaId(), referencia))
                .noneMatch(id -> id.filter(conta::equals).isPresent())) {
            throw new IllegalArgumentException("Conta de contrapartida incompatível com a operação de estoque.");
        }
        return conta;
    }

    private OperacaoEstoqueConfirmada confirmarOperacao(OperacaoEstoque operacao, Movimento movimento,
                                                       BigDecimal valor, String acao, List<SaldoPorConta> partidas) {
        // Calendário, autorização e produto já estão bloqueados. O lançamento é novo e privativo da transação.
        LancamentoContabil lancamento = contabilidade.escriturarTecnico(
                em.getReference(Empresa.class, movimento.empresaId()),
                em.getReference(Usuario.class, movimento.usuarioId()),
                em.getReference(ExercicioContabil.class, movimento.exercicioId()),
                em.getReference(PeriodoContabil.class, movimento.periodoId()),
                movimento.data(), operacao.historico(), OrigemLancamento.ESTOQUE, partidas);
        // A sequência permanece bloqueada. Releia a abertura antes de vincular e auditar a operação.
        contabilidade.validarIntegralizacaoEstoque(movimento.empresaId(), movimento.data());
        vincularLancamento(movimento, lancamento.getId());
        auditarMovimento(movimento, lancamento, acao, valor);
        return new OperacaoEstoqueConfirmada(movimento.id(), lancamento.getId(), lancamento.getNumero(),
                movimento.quantidade(), valor, movimento.valorTotal());
    }

    private void auditarMovimento(Movimento movimento, LancamentoContabil lancamento, String acao, BigDecimal valor) {
        jdbc.update("""
                insert into auditoria_evento (empresa_id, usuario_id, entidade, entidade_id, acao, dados_novos)
                values (?, ?, 'movimento_estoque', ?, ?, jsonb_build_object(
                    'produtoId', cast(? as uuid), 'dataContabil', cast(? as date),
                    'lancamentoId', cast(? as uuid), 'numeroContabil', cast(? as bigint),
                    'quantidade', cast(? as numeric), 'valorComercial', cast(? as numeric),
                    'custoEstoque', cast(? as numeric)))
                """, movimento.empresaId(), movimento.usuarioId(), movimento.id(), acao, movimento.produtoId(),
                movimento.data(), lancamento.getId(), lancamento.getNumero(), movimento.quantidade(), valor,
                movimento.valorTotal());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    Movimento registrarEntrada(Contexto contexto, BigDecimal quantidade, BigDecimal custoUnitario) {
        Entrada entrada = prepararEntrada(contexto, quantidade, custoUnitario);
        prepararMovimento(contexto);
        return gravarEntrada(entrada);
    }

    private Entrada prepararEntrada(Contexto contexto, BigDecimal quantidade, BigDecimal custoUnitario) {
        BigDecimal unidades = positivo(quantidade, 19, 6, "Quantidade");
        BigDecimal custo = positivo(custoUnitario, 19, 8, "Custo unitário");
        BigDecimal total = unidades.multiply(custo).setScale(2, RoundingMode.HALF_UP);
        if (total.signum() == 0) {
            throw new IllegalArgumentException("O custo total de uma entrada deve atingir pelo menos um centavo.");
        }

        return new Entrada(novoMovimento(contexto, Tipo.ENTRADA, unidades, total), custo);
    }

    private Movimento gravarEntrada(Entrada lote) {
        Movimento entrada = lote.movimento();
        movimentos.inserir(entrada);
        movimentos.inserirLote(entrada, lote.custoUnitario());
        return entrada;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    Saida registrarSaida(Contexto contexto, BigDecimal quantidade) {
        BigDecimal unidades = positivo(quantidade, 19, 6, "Quantidade");
        prepararMovimento(contexto);

        BigDecimal pendente = unidades;
        BigDecimal total = new BigDecimal("0.00");
        List<Alocacao> alocacoes = new ArrayList<>();
        for (Lote lote : movimentos.lotes(contexto.empresaId(), contexto.produtoId(), false)) {
            if (pendente.signum() == 0) {
                break;
            }
            BigDecimal retirar = pendente.min(lote.quantidadeRestante());
            BigDecimal valor = custoDaRetirada(lote, retirar);
            alocacoes.add(new Alocacao(lote.entradaId(), retirar, valor));
            total = total.add(valor);
            pendente = pendente.subtract(retirar);
        }
        if (pendente.signum() > 0) {
            throw new IllegalStateException("Estoque insuficiente para a saída.");
        }

        Movimento saida = novoMovimento(contexto, Tipo.SAIDA, unidades, total);
        movimentos.inserir(saida);
        for (Alocacao alocacao : alocacoes) {
            movimentos.consumir(saida, alocacao);
        }
        return new Saida(saida, alocacoes);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void vincularLancamento(Movimento movimento, UUID lancamentoId) {
        if (lancamentoId == null || !movimentos.vincularLancamento(movimento.empresaId(), movimento.id(), lancamentoId)) {
            throw new IllegalStateException("Movimento inexistente ou já vinculado a um lançamento.");
        }
    }

    private void prepararMovimento(Contexto contexto) {
        validarIsolamento();
        contabilidade.validarDestinoOperacional(contexto.empresaId(), contexto.usuarioId(),
                contexto.exercicioId(), contexto.periodoId(), contexto.data());
        ProdutoEstoque produto = bloquearProduto(contexto.empresaId(), contexto.produtoId());
        validarProdutoAtivo(produto);
        contabilidade.validarIntegralizacaoEstoque(contexto.empresaId(), contexto.data());
        LocalDate ultimaData = movimentos.ultimaData(contexto.empresaId(), produto.getId());
        if (ultimaData != null && contexto.data().isBefore(ultimaData)) {
            throw new IllegalStateException("A data não pode anteceder a última movimentação do produto.");
        }
    }

    private void validarIsolamento() {
        if (!"read committed".equals(jdbc.queryForObject("show transaction_isolation", String.class))) {
            throw new IllegalStateException("A movimentação de estoque exige READ COMMITTED.");
        }
    }

    private void validarProdutoAtivo(ProdutoEstoque produto) {
        if (!produto.isAtivo()) {
            throw new IllegalStateException("O produto deve estar ativo para movimentação.");
        }
    }

    private BigDecimal custoDaRetirada(Lote lote, BigDecimal retirar) {
        if (retirar.compareTo(lote.quantidadeRestante()) == 0) {
            return lote.valorRestante();
        }
        BigDecimal consumidoDepois = lote.quantidadeOriginal().subtract(lote.quantidadeRestante()).add(retirar);
        BigDecimal valorAcumulado = lote.valorOriginal().multiply(consumidoDepois)
                .divide(lote.quantidadeOriginal(), 2, RoundingMode.HALF_UP);
        return valorAcumulado.subtract(lote.valorOriginal().subtract(lote.valorRestante()));
    }

    private Movimento novoMovimento(Contexto contexto, Tipo tipo, BigDecimal quantidade, BigDecimal total) {
        return new Movimento(UUID.randomUUID(), contexto.empresaId(), contexto.produtoId(), contexto.exercicioId(),
                contexto.periodoId(), contexto.usuarioId(), contexto.data(), tipo, quantidade, total, null);
    }

    private ProdutoEstoque buscarProduto(UUID empresaId, UUID produtoId) {
        return produtos.findByIdAndEmpresaId(produtoId, empresaId)
                .orElseThrow(() -> new IllegalArgumentException("Produto não encontrado na empresa."));
    }

    private ProdutoEstoque bloquearProduto(UUID empresaId, UUID produtoId) {
        ProdutoEstoque produto = buscarProduto(empresaId, produtoId);
        em.refresh(produto, LockModeType.PESSIMISTIC_WRITE);
        return produto;
    }

    private Produto dados(ProdutoEstoque produto) {
        return new Produto(produto.getId(), produto.getEmpresaId(), produto.getCodigo(),
                produto.getDescricao(), produto.getUnidade(), produto.isAtivo());
    }

    private String textoObrigatorio(String valor, int limite, String campo, boolean normalizar) {
        String texto = valor == null ? "" : valor.trim();
        if (normalizar) {
            texto = texto.toUpperCase(Locale.ROOT);
        }
        if (texto.isEmpty() || texto.length() > limite) {
            throw new IllegalArgumentException(campo + " deve ter entre 1 e " + limite + " caracteres.");
        }
        return texto;
    }

    private BigDecimal positivo(BigDecimal valor, int precisao, int escala, String campo) {
        if (valor == null || valor.signum() <= 0) {
            throw new IllegalArgumentException(campo + " deve ser positivo.");
        }
        if ((long) valor.precision() - valor.scale() > precisao - escala) {
            throw new IllegalArgumentException(campo + " excede a precisão permitida.");
        }
        try {
            return valor.setScale(escala, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(campo + " excede a escala permitida.", e);
        }
    }

    public record Produto(UUID id, UUID empresaId, String codigo, String descricao, String unidade, boolean ativo) {
    }

    public record Ficha(Produto produto, Saldo saldo, List<Lote> lotes, List<Movimento> movimentos,
                        List<ReversaoOperacional> reversoes) {
        public Ficha {
            lotes = List.copyOf(lotes);
            movimentos = List.copyOf(movimentos);
            reversoes = List.copyOf(reversoes);
        }
    }

    record Contexto(UUID empresaId, UUID usuarioId, UUID exercicioId, UUID periodoId, UUID produtoId, LocalDate data) {
    }

    private record Entrada(Movimento movimento, BigDecimal custoUnitario) {
    }

    record Saida(Movimento movimento, List<Alocacao> alocacoes) {
        Saida {
            alocacoes = List.copyOf(alocacoes);
        }
    }
}
