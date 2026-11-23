
package br.com.lex.domain.contabil.repository;

import br.com.lex.domain.contabil.entity.Usuario;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface UsuarioRepository extends JpaRepository<Usuario, UUID> {
}
