
package br.com.lex.domain.contabil.repository;

import br.com.lex.domain.contabil.entity.PartidaContabil;
import br.com.lex.domain.contabil.enums.TipoPartida;
import org.springframework.data.jpa.repository.JpaRepository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PartidaContabilRepository
        extends JpaRepository<PartidaContabil, UUID> {

    Optional<PartidaContabil> findByIdAndLancamentoId(UUID id, UUID lancamentoId);

    List<PartidaContabil> findAllByLancamentoIdOrderByIdAsc(
            UUID lancamentoId
    );

    List<DadosPartida> findAllProjetadasByLancamentoIdOrderByIdAsc(UUID lancamentoId);

    interface DadosPartida {
        UUID getContaContabilId();
        TipoPartida getTipo();
        BigDecimal getValor();
        String getHistorico();
    }

    List<PartidaContabil>
    findAllByLancamentoIdAndPlanoContasIdOrderByIdAsc(
            UUID lancamentoId,
            UUID planoContasId
    );

    boolean existsByLancamentoId(UUID lancamentoId);

    long countByLancamentoId(UUID lancamentoId);
}
