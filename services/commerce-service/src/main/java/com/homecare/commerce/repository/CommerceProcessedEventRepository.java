package com.homecare.commerce.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.homecare.commerce.entity.CommerceProcessedEventEntity;

public interface CommerceProcessedEventRepository extends JpaRepository<CommerceProcessedEventEntity, UUID> {
}
