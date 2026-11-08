package com.homecare.household.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.homecare.household.entity.HouseholdOutboxEvent;

public interface HouseholdOutboxEventRepository extends JpaRepository<HouseholdOutboxEvent, UUID> {
    List<HouseholdOutboxEvent> findByStatusOrderByCreatedAtAsc(String status);
}
