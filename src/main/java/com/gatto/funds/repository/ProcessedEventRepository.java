package com.gatto.funds.repository;

import com.gatto.funds.domain.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, UUID> {

    /** Dedup fast-path: has this consumer already handled this event? */
    boolean existsByConsumerAndEventId(String consumer, UUID eventId);
}
