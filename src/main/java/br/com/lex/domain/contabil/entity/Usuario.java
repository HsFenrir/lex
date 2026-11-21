package br.com.lex.domain.contabil.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Entity
@Table(name = "usuario")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Usuario {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "nome", nullable = false, length = 150)
    private String nome;

    @Column(name = "email", nullable = false, length = 254)
    private String email;

    @Column(name = "senha_hash", nullable = false, length = 255)
    private String senhaHash;

    @Column(name = "ativo", nullable = false)
    private boolean ativo = true;

    @Column(
            name = "criado_em",
            nullable = false,
            insertable = false,
            updatable = false
    )
    private OffsetDateTime criadoEm;

    public Usuario(String nome, String email, String senhaHash) {
        this.nome = nome;
        this.email = email;
        this.senhaHash = senhaHash;
    }
}
