package com.homecare.booking.entity;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "quotation_items")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuotationItemEntity {

    @Id
    private UUID id;

    @Column(name = "quotation_id", nullable = false)
    private UUID quotationId;

    @Column(name = "row_index", nullable = false)
    private int rowIndex;

    @Column(name = "item_type", nullable = false, length = 32)
    private String itemType;

    @Column(nullable = false, length = 500)
    private String description;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "unit_price", nullable = false)
    private long unitPrice;

    @Column(name = "line_total", nullable = false)
    private long lineTotal;
}
