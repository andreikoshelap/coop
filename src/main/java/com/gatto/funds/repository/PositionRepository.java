package com.gatto.funds.repository;

import com.gatto.funds.domain.Position;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PositionRepository extends JpaRepository<Position, UUID> {

    Optional<Position> findByAccountIdAndIsin(Long accountId, String isin);
}
