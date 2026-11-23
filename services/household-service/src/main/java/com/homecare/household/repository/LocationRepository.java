package com.homecare.household.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.homecare.household.entity.Location;
import com.homecare.household.entity.LocationLevel;

public interface LocationRepository extends JpaRepository<Location, Integer> {

    @Modifying
    @Transactional
    @Query("DELETE FROM Location l WHERE l.level = :level")
    void deleteAllByLevel(@Param("level") LocationLevel level);

    Optional<Location> findByLevelAndOfficialCode(LocationLevel level, String officialCode);

    Optional<Location> findByLevelAndOfficialCodeAndActiveTrue(LocationLevel level, String officialCode);

    Optional<Location> findByParentIdAndOfficialCode(Integer parentId, String officialCode);

    Optional<Location> findByOfficialCodeAndActiveTrue(String officialCode);

    @Query("""
            SELECT l FROM Location l
            WHERE l.level = 'PROVINCE'
              AND l.active = true
              AND (:q IS NULL OR :q = '' OR LOWER(l.name) LIKE LOWER(CONCAT('%', :q, '%')) OR LOWER(l.codename) LIKE LOWER(CONCAT('%', :q, '%')))
            ORDER BY l.sortOrder ASC, l.name ASC
            """)
    List<Location> searchActiveProvinces(@Param("q") String query);

    @Query("""
            SELECT l FROM Location l
            WHERE l.level = 'WARD'
              AND l.parentId = :provinceId
              AND l.active = true
              AND (:q IS NULL OR :q = '' OR LOWER(l.name) LIKE LOWER(CONCAT('%', :q, '%')) OR LOWER(l.codename) LIKE LOWER(CONCAT('%', :q, '%')))
            ORDER BY l.sortOrder ASC, l.name ASC
            """)
    List<Location> searchActiveWards(@Param("provinceId") Integer provinceId, @Param("q") String query);
}
