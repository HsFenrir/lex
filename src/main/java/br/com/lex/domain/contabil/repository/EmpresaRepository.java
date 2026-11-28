
package br.com.lex.domain.contabil.repository;

import br.com.lex.domain.contabil.entity.Empresa;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EmpresaRepository extends JpaRepository<Empresa, UUID> {

    Optional<Empresa> findByIdAndUsuarioId(UUID empresaId, UUID usuarioId);

    List<Empresa> findAllByUsuarioIdOrderByRazaoSocialAscIdAsc(UUID usuarioId);
}
