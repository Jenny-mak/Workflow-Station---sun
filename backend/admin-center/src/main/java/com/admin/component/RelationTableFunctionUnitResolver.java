package com.admin.component;

import com.admin.dto.response.FunctionUnitTableGroupResponse;
import com.admin.entity.FunctionUnit;
import com.admin.entity.RelationTableFunctionUnit;
import com.admin.repository.FunctionUnitRepository;
import com.admin.repository.RelationTableFunctionUnitRepository;
import com.platform.common.version.SemanticVersion;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Resolves the many-to-many Relation Table <-> Function Unit links for display, shared by every
 * service that returns a {@code RelationTableResponse} (Table Structure CRUD, Table Data, Deploy).
 * A table with no rows in {@code rt_table_function_units} is Common (visible to all Function Units).
 */
@Component
@RequiredArgsConstructor
public class RelationTableFunctionUnitResolver {

    private final RelationTableFunctionUnitRepository relationTableFunctionUnitRepository;
    private final FunctionUnitRepository functionUnitRepository;

    /** Resolves the linked Function Units for a single table (two lookups: links, then units). */
    public List<FunctionUnit> resolveOne(Long tableId) {
        List<RelationTableFunctionUnit> links = relationTableFunctionUnitRepository.findByRelationTableId(tableId);
        if (links.isEmpty()) {
            return List.of();
        }
        Map<String, FunctionUnit> byId = functionUnitRepository
                .findAllById(links.stream().map(RelationTableFunctionUnit::getFunctionUnitId).distinct().toList())
                .stream().collect(Collectors.toMap(FunctionUnit::getId, Function.identity()));
        return resolve(links, byId);
    }

    /** Batch-loads the link rows for a set of tables, avoiding N+1 lookups. */
    public Map<Long, List<RelationTableFunctionUnit>> loadLinksByTable(List<Long> tableIds) {
        if (tableIds == null || tableIds.isEmpty()) {
            return new HashMap<>();
        }
        return relationTableFunctionUnitRepository.findByRelationTableIdIn(tableIds).stream()
                .collect(Collectors.groupingBy(RelationTableFunctionUnit::getRelationTableId));
    }

    /** Batch-resolves every Function Unit referenced by the given links, avoiding N+1 lookups. */
    public Map<String, FunctionUnit> loadFunctionUnitsById(Map<Long, List<RelationTableFunctionUnit>> linksByTable) {
        List<String> ids = linksByTable.values().stream()
                .flatMap(List::stream)
                .map(RelationTableFunctionUnit::getFunctionUnitId)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return new HashMap<>();
        }
        return functionUnitRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(FunctionUnit::getId, Function.identity()));
    }

    public List<FunctionUnit> resolve(List<RelationTableFunctionUnit> links, Map<String, FunctionUnit> functionUnitsById) {
        if (links == null || links.isEmpty()) {
            return List.of();
        }
        return links.stream()
                .map(l -> functionUnitsById.get(l.getFunctionUnitId()))
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * Collapses the links into one nav group per Function Unit <em>code</em>, sorted by display name.
     * Every publish or import of a unit adds another {@code sys_function_units} row under the same
     * code, so grouping by id listed the same unit once per version. Tables are counted distinctly
     * and the name comes from the newest version, so a renamed re-import shows its current name.
     */
    public List<FunctionUnitTableGroupResponse> groupByFunctionUnitCode(
            Map<Long, List<RelationTableFunctionUnit>> linksByTable,
            Map<String, FunctionUnit> functionUnitsById) {
        Map<String, Set<Long>> tableIdsByCode = new LinkedHashMap<>();
        Map<String, FunctionUnit> newestByCode = new LinkedHashMap<>();
        linksByTable.forEach((tableId, links) -> links.forEach(link -> {
            FunctionUnit unit = functionUnitsById.get(link.getFunctionUnitId());
            if (unit == null) {
                // function_unit_id is ON DELETE CASCADE, so an unresolvable link means the catalog
                // row vanished without its links — corruption, not "this unit has no tables".
                throw new IllegalStateException("relation table " + tableId
                        + " links missing function unit " + link.getFunctionUnitId());
            }
            tableIdsByCode.computeIfAbsent(unit.getCode(), code -> new LinkedHashSet<>()).add(tableId);
            newestByCode.merge(unit.getCode(), unit, RelationTableFunctionUnitResolver::newerVersion);
        }));
        return tableIdsByCode.entrySet().stream()
                .map(entry -> FunctionUnitTableGroupResponse.builder()
                        .functionUnitCode(entry.getKey())
                        .functionUnitName(newestByCode.get(entry.getKey()).getName())
                        .tableCount(entry.getValue().size())
                        .build())
                .sorted(Comparator.comparing(
                        g -> g.getFunctionUnitName() != null ? g.getFunctionUnitName() : g.getFunctionUnitCode(),
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    /** Newest catalog row of one code; ties and non-semver labels fall back to text order. */
    private static FunctionUnit newerVersion(FunctionUnit a, FunctionUnit b) {
        try {
            return SemanticVersion.parse(a.getVersion()).compareTo(SemanticVersion.parse(b.getVersion())) >= 0 ? a : b;
        } catch (IllegalArgumentException e) {
            return Objects.toString(a.getVersion(), "").compareTo(Objects.toString(b.getVersion(), "")) >= 0 ? a : b;
        }
    }
}
