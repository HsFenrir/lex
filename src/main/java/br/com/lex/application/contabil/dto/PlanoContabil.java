package br.com.lex.application.contabil.dto;

import br.com.lex.domain.contabil.enums.ClassificacaoBP;
import br.com.lex.domain.contabil.enums.ClassificacaoDRE;
import br.com.lex.domain.contabil.enums.GrupoConta;
import br.com.lex.domain.contabil.enums.NaturezaConta;
import br.com.lex.domain.contabil.enums.TipoConta;

import java.util.List;
import java.util.UUID;

public record PlanoContabil(UUID id, UUID empresaId, int modeloVersao, List<Conta> contas) {

    public PlanoContabil {
        contas = List.copyOf(contas);
    }

    public record Conta(UUID id, String codigo, String descricao, TipoConta tipo, NaturezaConta natureza,
                        GrupoConta grupo, ClassificacaoBP classificacaoBP, ClassificacaoDRE classificacaoDRE,
                        UUID contaPaiId, boolean redutora, UUID contaReduzidaId, boolean ativa,
                        UUID modeloContaId) {}
}
