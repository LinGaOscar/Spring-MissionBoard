package com.wbsflow.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiResponseTest {

    @Test
    void okWrapsDataWithSuccessTrueAndNullMessage() {
        ApiResponse<String> res = ApiResponse.ok("hello");

        assertThat(res.isSuccess()).isTrue();
        assertThat(res.getData()).isEqualTo("hello");
        assertThat(res.getMessage()).isNull();
    }

    @Test
    void errorWrapsMessageWithSuccessFalseAndNullData() {
        ApiResponse<Void> res = ApiResponse.error("失敗原因");

        assertThat(res.isSuccess()).isFalse();
        assertThat(res.getMessage()).isEqualTo("失敗原因");
        assertThat(res.getData()).isNull();
    }
}
