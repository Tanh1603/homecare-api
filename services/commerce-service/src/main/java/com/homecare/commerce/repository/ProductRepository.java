package com.homecare.commerce.repository;

import com.homecare.commerce.entity.ProductEntity;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ProductRepository extends JpaRepository<ProductEntity, UUID> {
    @Query("""
        select p from ProductEntity p where p.status = 'ACTIVE'
        and (:category is null or lower(p.category) = lower(:category))
        and (:q is null or lower(p.name) like lower(concat('%', :q, '%')) or lower(p.sku) like lower(concat('%', :q, '%')))
        and (:merchantId is null or p.merchantId = :merchantId)
        and (:minPrice is null or p.unitPrice >= :minPrice)
        and (:maxPrice is null or p.unitPrice <= :maxPrice)
        and (:inStock is null or :inStock = false or exists (select i.id from InventoryEntity i where i.productId = p.id and i.availableQty > 0))
        """)
    Page<ProductEntity> search(String category, String q, UUID merchantId,
            Long minPrice, Long maxPrice, Boolean inStock, Pageable pageable);
    List<ProductEntity> findByStatusOrderByCreatedAtDesc(String status);
}
