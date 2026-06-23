package org.bgm.orderservice.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ManyToOne;

@Entity
public class OrderItem extends BaseModel{
    @ManyToOne
    private Order order;
    private long productId;
    private int quantity;
    private Double unitPrice;
}
