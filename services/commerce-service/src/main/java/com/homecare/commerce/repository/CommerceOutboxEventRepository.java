package com.homecare.commerce.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.homecare.commerce.entity.CommerceOutboxEventEntity;

public interface CommerceOutboxEventRepository extends JpaRepository<CommerceOutboxEventEntity, UUID> {
    List<CommerceOutboxEventEntity> findByStatusOrderByCreatedAtAsc(String status);
}
