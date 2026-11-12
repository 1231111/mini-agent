package com.miniagent.agent.tool;

import com.miniagent.agent.tool.impl.*;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BrowserFormSchemaTest {
    @Test void modelSeesArrayParametersRatherThanStrings() {
        ToolRegistry registry = new ToolRegistry();
        registry.register("browser_select", "select", BrowserSelectParams.class, params -> "ok");
        registry.register("browser_upload", "upload", BrowserUploadParams.class, params -> "ok");
        registry.register("browser_form_verify", "verify", BrowserFormVerifyParams.class, params -> "ok");
        for (var spec : registry.getSpecifications()) {
            JsonObjectSchema schema = (JsonObjectSchema)spec.parameters();
            String key = spec.name().equals("browser_select") ? "values" : spec.name().equals("browser_upload") ? "paths" : "expectedValues";
            JsonArraySchema array = assertInstanceOf(JsonArraySchema.class, schema.properties().get(key));
            assertInstanceOf(JsonStringSchema.class,array.items());
        }
    }
    @Test void malformedArrayItemsAreRejectedBeforeDispatch() {
        var schema = ToolParams.generateSchema(BrowserSelectParams.class);
        assertFalse(ToolArgumentValidator.validate(schema, "{\"ref\":\"b1.2\",\"values\":[{}]}").valid());
        assertFalse(ToolArgumentValidator.validate(schema, "{\"ref\":\"b1.2\",\"values\":[null]}").valid());
        assertTrue(ToolArgumentValidator.validate(schema, "{\"ref\":\"b1.2\",\"values\":[\"rd\"]}").valid());
    }
}
