package com.gatto.funds.repository;

import com.gatto.funds.domain.OutboxEvent;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {

    /** The relay reads the oldest unpublished events first. */
    List<OutboxEvent> findByPublishedAtIsNullOrderByCreatedAtAsc(Limit limit);
}
