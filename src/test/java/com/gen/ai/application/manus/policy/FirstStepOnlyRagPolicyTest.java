package com.gen.ai.application.manus.policy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FirstStepOnlyRagPolicyTest {

    @Test
    void onlyStepOneUsesRag() {
        FirstStepOnlyRagPolicy p = new FirstStepOnlyRagPolicy();
        assertThat(p.useRag(1)).isTrue();
        assertThat(p.useRag(2)).isFalse();
        assertThat(p.useRag(99)).isFalse();
    }
}
