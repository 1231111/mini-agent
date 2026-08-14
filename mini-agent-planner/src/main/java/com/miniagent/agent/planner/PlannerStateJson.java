package com.miniagent.agent.planner;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/** Planner 快照 JSON 编解码（DB / Redis / 文件共用）。 */
public final class PlannerStateJson {

    private static final Logger log = LoggerFactory.getLogger(PlannerStateJson.class);
    public static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private static final TypeReference<List<DomainEvent>> EVENTS_TYPE = new TypeReference<>() {};

    private PlannerStateJson() {}

    public static String snapshotToJson(StateSnapshot snap) {
        try {
            return MAPPER.writeValueAsString(snap);
        } catch (Exception e) {
            throw new IllegalStateException("serialize planner snapshot: " + e.getMessage(), e);
        }
    }

    public static StateSnapshot snapshotFromJson(String json) {
        try {
            return MAPPER.readValue(json, StateSnapshot.class);
        } catch (Exception e) {
            throw new IllegalStateException("deserialize planner snapshot: " + e.getMessage(), e);
        }
    }

    public static String eventsToJson(List<DomainEvent> events) {
        try {
            return MAPPER.writeValueAsString(events == null ? List.of() : events);
        } catch (Exception e) {
            throw new IllegalStateException("serialize planner events: " + e.getMessage(), e);
        }
    }

    public static List<DomainEvent> eventsFromJson(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return MAPPER.readValue(json, EVENTS_TYPE);
        } catch (Exception e) {
            log.warn("planner events 反序列化失败，忽略: {}", e.getMessage());
            return List.of();
        }
    }
}
