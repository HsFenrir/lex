package br.com.lex.application.contabil.service;

import br.com.lex.domain.contabil.entity.ProdutoEstoque;
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

    private final ProdutoEstoqueRepository produtos;
    private final MovimentoEstoqueRepository movimentos;
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
                movimentos.lotes(empresaId, produtoId, true), movimentos.historico(empresaId, produtoId));
    }

    @Transactional(readOnly = true)
    public List<Alocacao> consultarAlocacoes(UUID empresaId, UUID usuarioId, UUID produtoId, UUID saidaId) {
        contabilidade.validarAcessoOperacional(empresaId, usuarioId);
        buscarProduto(empresaId, produtoId);
        return List.copyOf(movimentos.alocacoes(empresaId, produtoId, saidaId));
    }

    // O 7B chama estes métodos dentro da transação que também confirma o lançamento ESTOQUE.
    @Transactional(propagation = Propagation.MANDATORY)
    Movimento registrarEntrada(Contexto contexto, BigDecimal quantidade, BigDecimal custoUnitario) {
        BigDecimal unidades = positivo(quantidade, 19, 6, "Quantidade");
        BigDecimal custo = positivo(custoUnitario, 19, 8, "Custo unitário");
        BigDecimal total = unidades.multiply(custo).setScale(2, RoundingMode.HALF_UP);
        if (total.signum() == 0) {
            throw new IllegalArgumentException("O custo total de uma entrada deve atingir pelo menos um centavo.");
        }

        prepararMovimento(contexto);
        Movimento entrada = novoMovimento(contexto, Tipo.ENTRADA, unidades, total);
        movimentos.inserir(entrada);
        movimentos.inserirLote(entrada, custo);
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
        if (!"read committed".equals(jdbc.queryForObject("show transaction_isolation", String.class))) {
            throw new IllegalStateException("A movimentação de estoque exige READ COMMITTED.");
        }
        contabilidade.validarDestinoOperacional(contexto.empresaId(), contexto.usuarioId(),
                contexto.exercicioId(), contexto.periodoId(), contexto.data());
        ProdutoEstoque produto = bloquearProduto(contexto.empresaId(), contexto.produtoId());
        if (!produto.isAtivo()) {
            throw new IllegalStateException("O produto deve estar ativo para movimentação.");
        }
        LocalDate ultimaData = movimentos.ultimaData(contexto.empresaId(), produto.getId());
        if (ultimaData != null && contexto.data().isBefore(ultimaData)) {
            throw new IllegalStateException("A data não pode anteceder a última movimentação do produto.");
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

    public record Ficha(Produto produto, Saldo saldo, List<Lote> lotes, List<Movimento> movimentos) {
        public Ficha {
            lotes = List.copyOf(lotes);
            movimentos = List.copyOf(movimentos);
        }
    }

    record Contexto(UUID empresaId, UUID usuarioId, UUID exercicioId, UUID periodoId, UUID produtoId, LocalDate data) {
    }

    record Saida(Movimento movimento, List<Alocacao> alocacoes) {
        Saida {
            alocacoes = List.copyOf(alocacoes);
        }
    }
}
