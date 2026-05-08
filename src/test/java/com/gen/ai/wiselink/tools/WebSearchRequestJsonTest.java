package com.gen.ai.wiselink.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class WebSearchRequestJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void deserialize_json_object_with_query() throws Exception {
        WebSearchRequest r = mapper.readValue("{\"query\":\"  无线耳机  \"}", WebSearchRequest.class);
        assertEquals("无线耳机", r.query());
    }

    @Test
    void deserialize_plain_json_string_root() throws Exception {
        WebSearchRequest r = mapper.readValue("\"2025年最火折叠屏\"", WebSearchRequest.class);
        assertEquals("2025年最火折叠屏", r.query());
    }
}
