package com.developer.security;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Role-less users must not receive a default capability role at login / refresh / current-user.
 */
class LegacyVirtualGroupRoleLookupTest {
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final LegacyVirtualGroupRoleLookup lookup = new LegacyVirtualGroupRoleLookup(jdbcTemplate);

    @Test
    void returnsBoundRoles() {
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), eq("u-1"))).thenReturn(List.of("TEAM_LEAD"));
        assertThat(lookup.findRoleCodes("u-1")).containsExactly("TEAM_LEAD");
    }

    @Test
    void noRoles_returnsEmptyInsteadOfDefaultDeveloper() {
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), eq("u-1"))).thenReturn(List.of());
        assertThat(lookup.findRoleCodes("u-1")).isEmpty();
    }

    @Test
    void lookupFailure_grantsNoRole() {
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), eq("u-1")))
                .thenThrow(new DataAccessResourceFailureException("db down"));
        assertThat(lookup.findRoleCodes("u-1")).isEmpty();
    }
}
