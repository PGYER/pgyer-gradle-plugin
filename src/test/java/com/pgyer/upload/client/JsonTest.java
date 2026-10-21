package com.pgyer.upload.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonTest {

    @Test
    void parsesThePgyerAnswerShapes() throws Exception {
        Object value = Json.parse(" {\"code\":0,\"message\":\"\",\"data\":{\"a\":null,\"b\":true,\"c\":[1,-2.5,\"x\"],"
                + "\"d\":\"\\u84b2\\u516c\\u82f1 \\\"q\\\" \\\\ \\/ \\n\",\"e\":{},\"f\":[]}} ");

        Map<?, ?> map = (Map<?, ?>) value;
        assertEquals(0L, map.get("code"));
        assertEquals("", map.get("message"));
        Map<?, ?> data = (Map<?, ?>) map.get("data");
        assertNull(data.get("a"));
        assertEquals(Boolean.TRUE, data.get("b"));
        assertEquals(Arrays.asList(1L, new BigDecimal("-2.5"), "x"), data.get("c"));
        assertEquals("蒲公英 \"q\" \\ / \n", data.get("d"));
        assertEquals(Map.of(), data.get("e"));
        assertEquals(List.of(), data.get("f"));
    }

    @Test
    void rejectsWhatIsNotJson() {
        for (String text : new String[] {"", "<html></html>", "{\"a\":1", "{\"a\":1} x", "[1,]", "{\"a\" 1}", "nul",
                "\"unterminated", "{'a':1}"}) {
            assertThrows(Json.ParseException.class, () -> Json.parse(text), text);
        }
        assertThrows(Json.ParseException.class, () -> Json.parse(null));
    }

    @Test
    void writesIndentedJsonThatParsesBack() throws Exception {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", "My \"App\"\n\t蒲公英\u0001");
        map.put("size", 12L);
        map.put("none", null);
        map.put("ok", true);
        map.put("list", List.of("a", 2L));
        map.put("empty", Map.of());

        String text = Json.write(map);

        assertEquals(map, Json.parse(text));
        assertEquals("{\n  \"name\": \"My \\\"App\\\"\\n\\t蒲公英\\u0001\",\n  \"size\": 12,\n  \"none\": null,\n"
                + "  \"ok\": true,\n  \"list\": [\n    \"a\",\n    2\n  ],\n  \"empty\": {}\n}\n", text);
    }
}
