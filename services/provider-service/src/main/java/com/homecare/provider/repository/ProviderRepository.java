package com.homecare.provider.repository;

import com.homecare.provider.entity.ProviderEntity;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ProviderRepository extends JpaRepository<ProviderEntity, UUID> {
    @Query("""
        select p from ProviderEntity p where p.status = 'ACTIVE'
        and (:city is null or lower(p.city) = lower(:city))
        and (:q is null or lower(p.name) like lower(concat('%', :q, '%')))
        """)
    Page<ProviderEntity> search(String city, String q, Pageable pageable);

    Optional<ProviderEntity> findByOrganizationId(UUID organizationId);

    boolean existsByOrganizationId(UUID organizationId);
}
