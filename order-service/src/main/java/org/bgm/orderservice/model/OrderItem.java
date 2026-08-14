package org.bgm.orderservice.model;

import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "order_item")
public class OrderItem extends BaseModel {
    @ManyToOne
    @JoinColumn(name = "order_id")
    private Order order;
    private long productId;
    private int quantity;
    private Double unitPrice;
}
