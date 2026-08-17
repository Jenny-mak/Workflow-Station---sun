package com.portal.service;

import com.portal.dto.MainTableViewImportResult;
import com.portal.dto.MainTableViewPortalDtos.FunctionUnitViewMenuItem;
import com.portal.dto.MainTableViewPortalDtos.MainTableViewColumnFilter;
import com.portal.dto.MainTableViewPortalDtos.MainTableViewDataPage;
import com.portal.dto.MainTableViewPortalDtos.MainTableViewSummary;

import java.util.List;

public interface PortalMainTableViewService {

    List<FunctionUnitViewMenuItem> listAccessibleFunctionUnits(String userId);

    List<MainTableViewSummary> listPublishedViews(String userId, String functionUnitCode);

    MainTableViewDataPage queryViewData(
            String userId,
            Long viewId,
            int page,
            int size,
            String search,
            List<MainTableViewColumnFilter> columnFilters,
            String sortField,
            String sortDirection,
            String groupBy);

    byte[] exportViewCsv(String userId, Long viewId, int maxRows);

    MainTableViewImportResult importViewCsv(String userId, Long viewId, byte[] csvBytes);
}
