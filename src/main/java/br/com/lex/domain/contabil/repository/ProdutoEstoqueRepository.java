package br.com.lex.domain.contabil.repository;

import br.com.lex.domain.contabil.entity.ProdutoEstoque;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProdutoEstoqueRepository extends JpaRepository<ProdutoEstoque, UUID> {

    Optional<ProdutoEstoque> findByIdAndEmpresaId(UUID id, UUID empresaId);

    List<ProdutoEstoque> findAllByEmpresaIdOrderByCodigoAsc(UUID empresaId);
}
