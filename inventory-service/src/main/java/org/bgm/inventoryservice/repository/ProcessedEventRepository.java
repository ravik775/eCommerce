package org.bgm.inventoryservice.repository;

import org.bgm.inventoryservice.model.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, String> {
}
