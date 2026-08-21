package com.portal.component;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portal.repository.ProcessInstanceRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * {@link RequestIdEnricher} core join logic: ordered fields + separator, empty-field skip,
 * and unconfigured → null. The PRIMARY-table config lookup is stubbed at the JdbcTemplate seam.
 */
class RequestIdEnricherTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @SuppressWarnings("unchecked")
    private RequestIdEnricher enricherReturning(String cfgJson) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        // resolveSpec queries one column (request_id_config text) keyed by functionUnitCode.
        when(jdbc.query(any(String.class), any(RowMapper.class), eq("FU1")))
                .thenReturn(cfgJson == null ? List.of() : List.of(cfgJson));
        ProcessInstanceRepository repo = mock(ProcessInstanceRepository.class);
        return new RequestIdEnricher(jdbc, objectMapper, repo);
    }

    private static Map<String, Object> vars(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void joinsOrderedFieldsWithSeparator() {
        RequestIdEnricher enricher = enricherReturning(
                "{\"fieldNames\":[\"dept\",\"year\",\"seq\"],\"separator\":\"-\"}");
        String id = enricher.buildRequestId("FU1", vars("dept", "HR", "year", 2026, "seq", "001"));
        assertThat(id).isEqualTo("HR-2026-001");
    }

    @Test
    void skipsEmptyFieldsToAvoidDoubleSeparator() {
        RequestIdEnricher enricher = enricherReturning(
                "{\"fieldNames\":[\"dept\",\"year\",\"seq\"],\"separator\":\"-\"}");
        // year missing → must not produce HR--001
        String id = enricher.buildRequestId("FU1", vars("dept", "HR", "seq", "001"));
        assertThat(id).isEqualTo("HR-001");
    }

    @Test
    void supportsEmptySeparator() {
        RequestIdEnricher enricher = enricherReturning(
                "{\"fieldNames\":[\"a\",\"b\"],\"separator\":\"\"}");
        assertThat(enricher.buildRequestId("FU1", vars("a", "X", "b", "Y"))).isEqualTo("XY");
    }

    @Test
    void returnsNullWhenUnconfigured() {
        RequestIdEnricher enricher = enricherReturning(null);
        assertThat(enricher.buildRequestId("FU1", vars("dept", "HR"))).isNull();
    }

    @Test
    void returnsNullWhenAllSelectedFieldsEmpty() {
        RequestIdEnricher enricher = enricherReturning(
                "{\"fieldNames\":[\"dept\",\"seq\"],\"separator\":\"-\"}");
        assertThat(enricher.buildRequestId("FU1", vars("other", "x"))).isNull();
    }

    @Test
    void returnsNullForBlankInputs() {
        RequestIdEnricher enricher = enricherReturning(
                "{\"fieldNames\":[\"dept\"],\"separator\":\"-\"}");
        assertThat(enricher.buildRequestId(null, vars("dept", "HR"))).isNull();
        assertThat(enricher.buildRequestId("FU1", null)).isNull();
    }

    @Test
    void cachesPrimaryTableConfigAcrossListPages() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(any(String.class), any(RowMapper.class), eq("FU1")))
                .thenReturn(List.of("{\"fieldNames\":[\"dept\"],\"separator\":\"-\"}"));
        RequestIdEnricher enricher = new RequestIdEnricher(
                jdbc, objectMapper, mock(ProcessInstanceRepository.class));

        assertThat(enricher.buildRequestId("FU1", vars("dept", "HR"))).isEqualTo("HR");
        assertThat(enricher.buildRequestId("FU1", vars("dept", "IT"))).isEqualTo("IT");

        verify(jdbc, times(1)).query(any(String.class), any(RowMapper.class), eq("FU1"));
    }
}
