package br.com.lex.application.contabil.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

public record FichaAtivo(
        UUID id, UUID empresaId, UUID usuarioId, String codigo, String descricao, UUID contaPatrimonialId,
        LocalDate dataAquisicao, LocalDate dataDisponibilidade, BigDecimal custoAquisicao, boolean depreciavel,
        BigDecimal valorResidual, Integer vidaUtilMeses, BigDecimal baseDepreciavel, UUID contaRedutoraId,
        UUID contaDespesaId, Situacao situacao, UUID lancamentoId, UUID exercicioAquisicaoId, long numeroContabil,
        BigDecimal depreciacaoAcumulada, BigDecimal valorContabilLiquido, BigDecimal baseDepreciavelRemanescente,
        int vidaUtilConsumidaMeses, int vidaUtilRemanescenteMeses, List<ApropriacaoDepreciacao> apropriacoes,
        Origem origem, UUID lancamentoAberturaId, UUID exercicioAberturaId, LocalDate dataAbertura,
        YearMonth marcoImportacao, YearMonth primeiraCompetenciaLex, int mesesConsumidosNaImportacao,
        BigDecimal depreciacaoAcumuladaImportada, BigDecimal depreciacaoApropriadaLex
) {
    public FichaAtivo {
        apropriacoes = List.copyOf(apropriacoes);
    }

    public List<YearMonth> competenciasProcessadas() {
        return apropriacoes.stream().map(ApropriacaoDepreciacao::competencia).toList();
    }

    public enum Situacao {
        AGUARDANDO_DISPONIBILIDADE, DISPONIBILIDADE_REGISTRADA
    }

    public enum Origem {
        AQUISICAO, IMPORTACAO
    }
}
