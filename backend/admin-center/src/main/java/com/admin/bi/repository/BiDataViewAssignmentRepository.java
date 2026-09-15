package com.admin.bi.repository;

import com.admin.bi.entity.BiDataViewAssignment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BiDataViewAssignmentRepository extends JpaRepository<BiDataViewAssignment, String> {

    boolean existsByDashboardIdAndTableId(String dashboardId, Long tableId);

    boolean existsByDashboardIdAndTableIdAndIdNot(String dashboardId, Long tableId, String id);

    long countByDashboardId(String dashboardId);

    List<BiDataViewAssignment> findByTableIdOrderByCreatedAtAsc(Long tableId);

    @Query("SELECT a FROM BiDataViewAssignment a JOIN BiDashboardRegistry d ON a.dashboardId = d.id WHERE " +
            "(CAST(:dashboardTitle AS string) IS NULL OR LOWER(d.dashboardTitle) LIKE " +
            "LOWER(CONCAT('%', CAST(:dashboardTitle AS string), '%'))) AND " +
            "(:functionUnitId IS NULL OR a.functionUnitId = :functionUnitId)")
    Page<BiDataViewAssignment> findByFilters(
            @Param("dashboardTitle") String dashboardTitle,
            @Param("functionUnitId") Long functionUnitId,
            Pageable pageable);
}
