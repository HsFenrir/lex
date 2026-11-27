package br.com.lex.application.contabil.service;

import br.com.lex.application.contabil.dto.Balancete.Conta;
import br.com.lex.domain.contabil.enums.ClassificacaoBP;
import br.com.lex.domain.contabil.enums.ClassificacaoDRE;
import br.com.lex.domain.contabil.enums.GrupoConta;
import br.com.lex.domain.contabil.enums.TipoConta;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

final class ClassificacaoDemonstracoes {

    private ClassificacaoDemonstracoes() {
    }

    static boolean contaDeResultado(Conta conta) {
        return switch (conta.grupo()) {
            case RECEITA, CUSTO, DESPESA -> true;
            default -> false;
        };
    }

    static ClassificacaoBP classificarBP(Conta conta, Map<UUID, Conta> plano) {
        ClassificacaoBP classe = herdar(conta, plano, Conta::classificacaoBP);
        if (conta.contaRedutora()) {
            Conta reduzida = plano.get(conta.contaReduzidaId());
            if (reduzida == null || reduzida.grupo() != conta.grupo() || reduzida.tipo() != TipoConta.ANALITICA) {
                throw erro(conta, "vínculo com a conta reduzida incompatível");
            }
            ClassificacaoBP classeReduzida = herdar(reduzida, plano, Conta::classificacaoBP);
            if (classe == null) {
                classe = classeReduzida;
            } else if (classeReduzida != null && classe != classeReduzida) {
                throw erro(conta, "classificação de BP diverge da conta reduzida");
            }
        }
        if (classe == null && conta.grupo() == GrupoConta.PATRIMONIO_LIQUIDO) {
            classe = ClassificacaoBP.PATRIMONIO_LIQUIDO;
        }
        if (classe == null || grupoDaClasse(classe) != conta.grupo()) {
            throw erro(conta, "classificação de BP ausente ou incompatível com o grupo");
        }
        return classe;
    }

    private static GrupoConta grupoDaClasse(ClassificacaoBP classe) {
        return switch (classe) {
            case ATIVO_CIRCULANTE, ATIVO_NAO_CIRCULANTE -> GrupoConta.ATIVO;
            case PASSIVO_CIRCULANTE, PASSIVO_NAO_CIRCULANTE -> GrupoConta.PASSIVO;
            case PATRIMONIO_LIQUIDO -> GrupoConta.PATRIMONIO_LIQUIDO;
        };
    }

    static ClassificacaoDRE classificarDRE(Conta conta, Map<UUID, Conta> plano) {
        ClassificacaoDRE classe = herdar(conta, plano, Conta::classificacaoDRE);
        if (classe == null) {
            throw erro(conta, "classificação de DRE ausente");
        }
        return classe;
    }

    private static <T> T herdar(Conta conta, Map<UUID, Conta> plano, Function<Conta, T> classificacao) {
        Set<UUID> visitadas = new HashSet<>();
        for (Conta atual = conta; atual != null && atual.grupo() == conta.grupo();
             atual = plano.get(atual.contaPaiId())) {
            if (!visitadas.add(atual.id())) {
                throw erro(conta, "ciclo na hierarquia do plano");
            }
            if (atual != conta && atual.tipo() != TipoConta.SINTETICA) {
                throw erro(conta, "conta pai não sintética");
            }
            T classe = classificacao.apply(atual);
            if (classe != null) {
                return classe;
            }
        }
        return null;
    }

    private static IllegalStateException erro(Conta conta, String causa) {
        return new IllegalStateException("Conta " + conta.codigo() + " (" + conta.id() + "): " + causa + ".");
    }
}
