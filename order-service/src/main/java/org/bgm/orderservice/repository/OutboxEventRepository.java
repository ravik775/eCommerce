package org.bgm.orderservice.repository;

import org.bgm.orderservice.model.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {
    List<OutboxEvent> findByPublishedFalseOrderByCreatedAtAsc();
}
