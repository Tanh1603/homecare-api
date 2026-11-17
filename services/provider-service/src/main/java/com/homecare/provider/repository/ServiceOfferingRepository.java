package com.homecare.provider.repository;

import com.homecare.provider.entity.ServiceOffering;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ServiceOfferingRepository extends JpaRepository<ServiceOffering, UUID> {
    @Query("""
        select s from ServiceOffering s where s.active = true
        and exists (select 1 from ProviderEntity p where p.id = s.providerId and p.status = 'ACTIVE')
        and (:category is null or lower(s.category) = lower(:category))
        and (:providerId is null or s.providerId = :providerId)
        and (:q is null or lower(s.name) like lower(concat('%', :q, '%')))
        """)
    Page<ServiceOffering> search(String category, UUID providerId, String q, Pageable pageable);

    @Query("""
        select s from ServiceOffering s where (:category is null or lower(s.category) = lower(:category))
        and (:providerId is null or s.providerId = :providerId)
        and (:q is null or lower(s.name) like lower(concat('%', :q, '%')))
        """)
    Page<ServiceOffering> searchIncludingInactive(String category, UUID providerId, String q, Pageable pageable);
    List<ServiceOffering> findByActiveTrueOrderByCreatedAtDesc();
    List<ServiceOffering> findByProviderIdAndActiveTrue(UUID providerId);
}
