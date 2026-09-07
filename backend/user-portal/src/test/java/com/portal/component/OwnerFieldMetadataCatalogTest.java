package com.portal.component;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("OwnerFieldMetadataCatalog")
class OwnerFieldMetadataCatalogTest {

    @Test
    @DisplayName("negative existence is never reused from cache")
    void negativeExistenceIsNotCached() {
        assertThat(OwnerFieldMetadataCatalog.shouldReuseExistenceCache(false, 1L, 2L, 30_000L))
                .isFalse();
        assertThat(OwnerFieldMetadataCatalog.shouldReuseExistenceCache(true, 1L, 2L, 30_000L))
                .isTrue();
        assertThat(OwnerFieldMetadataCatalog.shouldReuseExistenceCache(true, 1L, 40_001L, 30_000L))
                .isFalse();
    }
}
