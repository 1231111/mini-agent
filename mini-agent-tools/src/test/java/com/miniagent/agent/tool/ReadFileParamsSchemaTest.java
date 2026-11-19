package com.miniagent.agent.tool;

import com.miniagent.agent.tool.impl.ReadFileParams;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReadFileParamsSchemaTest {

    @Test
    void exposesRangeParameterNamesThatMatchReadFileInstructions() {
        Map<String, Object> schema = ToolParams.generateSchema(ReadFileParams.class);

        assertTrue(schema.containsKey("char_offset"), schema.toString());
        assertTrue(schema.containsKey("char_limit"), schema.toString());
        assertFalse(schema.containsKey("charOffset"), schema.toString());
        assertFalse(schema.containsKey("charLimit"), schema.toString());
    }

    @Test
    void parsesSchemaNamesAndRetainsCamelCaseCompatibility() {
        ReadFileParams snakeCase = ToolParams.fromJson(
                "{\"path\":\"index.html\",\"offset\":399,\"limit\":1,\"char_offset\":8000,\"char_limit\":4000}",
                ReadFileParams.class);
        assertEquals(8000, snakeCase.getCharOffset());
        assertEquals(4000, snakeCase.getCharLimit());
        assertTrue(snakeCase.hasCharRange());

        ReadFileParams camelCase = ToolParams.fromJson(
                "{\"path\":\"index.html\",\"charOffset\":4000,\"charLimit\":2000}",
                ReadFileParams.class);
        assertEquals(4000, camelCase.getCharOffset());
        assertEquals(2000, camelCase.getCharLimit());
    }
}
