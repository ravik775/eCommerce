package org.bgm.inventoryservice.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "inventory")
public class Inventory {
    @Id
    private Long productId;
    private int availableQty;
    private int reservedQty;
    @Version
    private long version;
}
