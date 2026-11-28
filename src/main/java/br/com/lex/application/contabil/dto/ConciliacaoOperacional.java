package br.com.lex.application.contabil.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record ConciliacaoOperacional(UUID empresaId, UUID exercicioId, LocalDate dataBase, List<Item> itens) {
    public ConciliacaoOperacional {
        itens = List.copyOf(itens);
    }

    public record Item(Modulo modulo, Balancete.Conta conta, BigDecimal saldoContabil, BigDecimal saldoOperacional,
                       BigDecimal diferenca, Diagnostico diagnostico, BigDecimal saldoPendenteDeImportacao,
                       BigDecimal saldoManualLiquido) {
    }

    public enum Modulo {
        ESTOQUE, IMOBILIZADO, DEPRECIACAO_ACUMULADA
    }

    public enum Diagnostico {
        SEM_NECESSIDADE_DE_CONTROLE, CONCILIADO, SALDO_PENDENTE_DE_IMPORTACAO,
        DIVERGENCIA_LANCAMENTO_MANUAL, DIVERGENCIA_OPERACIONAL
    }
}
