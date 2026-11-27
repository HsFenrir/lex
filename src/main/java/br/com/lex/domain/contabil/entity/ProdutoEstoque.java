package br.com.lex.domain.contabil.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Entity
@Table(name = "produto_estoque")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProdutoEstoque {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "empresa_id", nullable = false)
    private UUID empresaId;

    @Column(nullable = false, length = 50)
    private String codigo;

    @Column(nullable = false, length = 200)
    private String descricao;

    @Column(nullable = false, length = 16)
    private String unidade;

    @Column(nullable = false)
    private boolean ativo = true;

    public ProdutoEstoque(UUID empresaId, String codigo, String descricao, String unidade) {
        this.empresaId = empresaId;
        this.codigo = codigo;
        this.descricao = descricao;
        this.unidade = unidade;
    }

    public void alterarAtividade(boolean ativo) {
        this.ativo = ativo;
    }
}
