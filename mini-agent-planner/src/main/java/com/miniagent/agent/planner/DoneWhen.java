package com.miniagent.agent.planner;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;

/**
 * 节点完成条件。快照里兼容旧的字符串 wire（file_exists:path）。
 */
@JsonDeserialize(using = DoneWhen.Json.class)
public record DoneWhen(String type, String criteria, String path) {

    public static final String NOTE = "note_required";
    public static final String FILE = "file_exists";
    public static final String MEDIA = "media_delivered";
    public static final String JUDGE = "llm_judge";
    public static final String COMMAND = "command_success";
    public static final String VALIDATION = "validation_passed";

    public DoneWhen {
        type = type == null ? NOTE : type.trim();
        criteria = criteria == null ? "" : criteria.trim();
        path = path == null ? "" : path.trim();
    }

    public static DoneWhen note() {
        return new DoneWhen(NOTE, "", "");
    }

    public static DoneWhen file(String path) {
        return new DoneWhen(FILE, "", path);
    }

    public static DoneWhen file(String path, String criteria) {
        return new DoneWhen(FILE, criteria, path);
    }

    public static DoneWhen media() {
        return new DoneWhen(MEDIA, "", "");
    }

    public static DoneWhen judge(String criteria) {
        return new DoneWhen(JUDGE, criteria, "");
    }

    public static DoneWhen command() {
        return new DoneWhen(COMMAND, "", "");
    }

    public static DoneWhen validation(String criteria) {
        return new DoneWhen(VALIDATION, criteria, "");
    }

    public boolean isFile() {
        return FILE.equalsIgnoreCase(type);
    }

    public boolean isMedia() {
        return MEDIA.equalsIgnoreCase(type);
    }

    public boolean isJudge() {
        return JUDGE.equalsIgnoreCase(type);
    }

    public boolean isNote() {
        return type.isBlank() || NOTE.equalsIgnoreCase(type);
    }

    public boolean isCommand() {
        return COMMAND.equalsIgnoreCase(type);
    }

    public boolean isValidation() {
        return VALIDATION.equalsIgnoreCase(type);
    }

    public boolean worldCheck() {
        return isFile() || isMedia();
    }

    public boolean valid() {
        if (isFile()) return StringUtils.isNotBlank(path);
        if (isJudge()) return StringUtils.isNotBlank(criteria);
        return isNote() || isMedia() || isCommand() || isValidation();
    }

    /** Todo / 语义校验仍吃前缀字符串。 */
    public String wire() {
        if (isFile()) return FILE + ":" + path;
        if (isJudge()) return JUDGE + ":" + criteria;
        if (isMedia()) return MEDIA;
        if (isCommand()) return COMMAND;
        if (isValidation())
            return criteria.isBlank() ? VALIDATION : VALIDATION + ":" + criteria;
        return NOTE;
    }

    public static DoneWhen parse(JsonNode n) {
        if (n == null || n.isNull()) return note();
        if (n.isTextual()) return parseWire(n.asText());
        if (!n.isObject()) return note();
        String t = text(n, "type", NOTE);
        return new DoneWhen(t, text(n, "criteria", ""), text(n, "path", ""));
    }

    public static DoneWhen parseWire(String s) {
        if (StringUtils.isBlank(s)) return note();
        String t = s.trim();
        if (t.regionMatches(true, 0, FILE + ":", 0, FILE.length() + 1))
            return file(t.substring(FILE.length() + 1).trim());
        if (t.regionMatches(true, 0, JUDGE + ":", 0, JUDGE.length() + 1))
            return judge(t.substring(JUDGE.length() + 1).trim());
        if (MEDIA.equalsIgnoreCase(t)) return media();
        if (NOTE.equalsIgnoreCase(t)) return note();
        if (COMMAND.equalsIgnoreCase(t)) return command();
        if (VALIDATION.equalsIgnoreCase(t)) return validation("");
        if (t.regionMatches(true, 0, VALIDATION + ":", 0, VALIDATION.length() + 1))
            return validation(t.substring(VALIDATION.length() + 1).trim());
        return new DoneWhen(t, "", "");
    }

    private static String text(JsonNode n, String field, String def) {
        if (!n.has(field) || n.get(field).isNull()) return def;
        String v = n.get(field).asText();
        return StringUtils.isBlank(v) ? def : v;
    }

    public static final class Json extends JsonDeserializer<DoneWhen> {
        @Override
        public DoneWhen deserialize(JsonParser p, DeserializationContext ctxt)
                throws IOException {
            return DoneWhen.parse(p.getCodec().readTree(p));
        }
    }
}
