package com.homecare.provider.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.homecare.provider.entity.ProviderEntity;

public interface ProviderRepository extends JpaRepository<ProviderEntity, UUID> {
}
