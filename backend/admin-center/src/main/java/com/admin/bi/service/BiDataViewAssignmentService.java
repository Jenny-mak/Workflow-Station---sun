package com.admin.bi.service;

import com.admin.bi.dto.request.DataViewAssignmentRequest;
import com.admin.bi.dto.request.DataViewAssignmentBatchRequest;
import com.admin.bi.dto.response.DataViewAssignmentResponse;
import com.admin.bi.dto.response.DataViewDashboardResponse;
import com.admin.bi.dto.response.DataViewFunctionUnitOptionResponse;
import com.admin.bi.dto.response.DataViewTableOptionResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface BiDataViewAssignmentService {
    DataViewAssignmentResponse create(DataViewAssignmentRequest request);

    List<DataViewAssignmentResponse> createBatch(DataViewAssignmentBatchRequest request);

    Page<DataViewAssignmentResponse> list(String dashboardTitle, Long functionUnitId, Pageable pageable);

    DataViewAssignmentResponse update(String id, DataViewAssignmentRequest request);

    void delete(String id);

    List<DataViewFunctionUnitOptionResponse> listFunctionUnits();

    List<DataViewTableOptionResponse> listTables(Long functionUnitId);

    List<DataViewDashboardResponse> getDashboardsForView(String userId, Long viewId);

    boolean canAccessDashboardForView(String userId, String dashboardId, Long viewId);
}
