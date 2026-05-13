package com.gen.ai.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LocalhostOnlyAdminInterceptorTest {

    @Test
    void loopbackIpv4() {
        assertThat(LocalhostOnlyAdminInterceptor.isLoopback("127.0.0.1")).isTrue();
    }

    @Test
    void loopbackIpv6Compressed() {
        assertThat(LocalhostOnlyAdminInterceptor.isLoopback("::1")).isTrue();
    }

    @Test
    void notLoopback() {
        assertThat(LocalhostOnlyAdminInterceptor.isLoopback("192.168.1.1")).isFalse();
        assertThat(LocalhostOnlyAdminInterceptor.isLoopback("")).isFalse();
    }
}
