package com.portal.controller;

import com.platform.common.util.SafeUrlInput;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RestTemplate.exchange(String) re-encodes {@code %} in a pre-built query.
 * The people picker must pass {@link URI#create(String)} so 李 stays 李.
 */
class TaskControllerUserSearchUriTest {

    @Test
    void chineseKeywordIsPercentEncodedOnce() {
        String url = "http://admin-center:8080/api/v1/admin/users?keyword="
                + SafeUrlInput.encodeQueryValue("李") + "&size=20";
        URI uri = URI.create(url);
        assertThat(uri.getRawQuery()).contains("keyword=%E6%9D%8E");
        assertThat(uri.getRawQuery()).doesNotContain("%25E6");
        assertThat(uri.getQuery()).contains("keyword=李");
    }
}
