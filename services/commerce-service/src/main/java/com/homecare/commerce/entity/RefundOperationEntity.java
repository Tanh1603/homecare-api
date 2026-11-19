package com.homecare.commerce.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "commerce_refund_operations")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RefundOperationEntity {

    @Id
    private UUID id;

    @Column(name = "return_request_id", nullable = false, unique = true)
    private UUID returnRequestId;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "txn_ref", length = 100)
    private String txnRef;

    @Column(name = "vnp_transaction_no", length = 64)
    private String vnpTransactionNo;

    @Column(nullable = false)
    private long amount;

    @Column(nullable = false, length = 48)
    private String status;

    @Column(name = "provider_reference", length = 128)
    private String providerReference;

    @Column(name = "actor_profile_id", length = 255)
    private String actorProfileId;

    @Column(length = 500)
    private String note;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
