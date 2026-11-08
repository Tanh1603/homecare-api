package com.homecare.commerce.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.homecare.commerce.entity.MerchantEntity;

public interface MerchantRepository extends JpaRepository<MerchantEntity, UUID> {
}
