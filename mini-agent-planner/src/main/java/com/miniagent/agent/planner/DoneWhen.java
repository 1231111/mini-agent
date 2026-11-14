package com.miniagent.agent.planner;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 节点完成条件。快照里兼容旧的字符串 wire（file_exists:path）。
 */
@JsonDeserialize(using = DoneWhen.Json.class)
public record DoneWhen(String type, String criteria, String path, List<String> checks) {

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
        checks = checks == null ? List.of() : checks.stream()
                .map(check -> check == null ? "" : check.trim()).toList();
    }

    /** Compatibility for existing callers and snapshots without additional semantic checks. */
    public DoneWhen(String type, String criteria, String path) {
        this(type, criteria, path, List.of());
    }

    /** Each declared requirement is evaluated, rather than merely mentioned in a node's description. */
    public List<String> semanticChecks() {
        LinkedHashSet<String> all = new LinkedHashSet<>();
        if (!criteria.isBlank()) all.add(criteria);
        all.addAll(checks);
        return List.copyOf(all);
    }

    public DoneWhen withChecks(List<String> checks) {
        return new DoneWhen(type, criteria, path, checks);
    }

    public boolean verifies(String requirement) {
        return requirement != null && !requirement.isBlank()
                && semanticChecks().contains(requirement.trim());
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
        if (checks.stream().anyMatch(String::isBlank)) return false;
        if (isFile()) {
            return StringUtils.isNotBlank(path);
        }
        if (isJudge()) {
            return StringUtils.isNotBlank(criteria);
        }
        return isNote() || isMedia() || isCommand() || isValidation();
    }

    /**
     * LLM 常把验收路径写成 /tmp/xxx，workspace 沙箱会拒写。
     * 只保留文件名，落到默认写出目录。
     */
    public DoneWhen sandboxWritePath() {
        if (!isFile() || StringUtils.isBlank(path)) {
            return this;
        }
        String p = path.replace('\\', '/');
        boolean tmp = p.startsWith("/tmp/") || p.startsWith("/var/tmp/")
                || p.contains("/tmp/")
                || p.matches("(?i)^[a-z]:/tmp/.*");
        if (!tmp) {
            return this;
        }
        int slash = p.lastIndexOf('/');
        String name = slash >= 0 ? p.substring(slash + 1) : p;
        if (StringUtils.isBlank(name)) {
            return new DoneWhen(FILE, criteria, "", checks);
        }
        return new DoneWhen(FILE, criteria, name, checks);
    }

    /** Todo / 语义校验仍吃前缀字符串。 */
    public String wire() {
        if (isFile()) {
            return FILE + ":" + path;
        }
        if (isJudge()) {
            return JUDGE + ":" + criteria;
        }
        if (isMedia()) {
            return MEDIA;
        }
        if (isCommand()) {
            return COMMAND;
        }
        if (isValidation())
            return criteria.isBlank() ? VALIDATION : VALIDATION + ":" + criteria;
        return NOTE;
    }

    public static DoneWhen parse(JsonNode n) {
        if (n == null || n.isNull()) {
            return note();
        }
        if (n.isTextual()) {
            return parseWire(n.asText());
        }
        if (!n.isObject()) {
            return note();
        }
        String t = text(n, "type", NOTE);
        List<String> checks = new ArrayList<>();
        JsonNode checkNodes = n.get("checks");
        if (checkNodes != null && !checkNodes.isNull()) {
            if (!checkNodes.isArray()) {
                checks.add(""); // Reject malformed contracts through valid(), rather than silently dropping them.
            } else {
                for (JsonNode check : checkNodes) checks.add(check.isTextual() ? check.asText() : "");
            }
        }
        return new DoneWhen(t, text(n, "criteria", ""), text(n, "path", ""), checks).sandboxWritePath();
    }

    public static DoneWhen parseWire(String s) {
        if (StringUtils.isBlank(s)) {
            return note();
        }
        String t = s.trim();
        if (t.regionMatches(true, 0, FILE + ":", 0, FILE.length() + 1))
            return file(t.substring(FILE.length() + 1).trim()).sandboxWritePath();
        if (t.regionMatches(true, 0, JUDGE + ":", 0, JUDGE.length() + 1))
            return judge(t.substring(JUDGE.length() + 1).trim());
        if (MEDIA.equalsIgnoreCase(t)) {
            return media();
        }
        if (NOTE.equalsIgnoreCase(t)) {
            return note();
        }
        if (COMMAND.equalsIgnoreCase(t)) {
            return command();
        }
        if (VALIDATION.equalsIgnoreCase(t)) {
            return validation("");
        }
        if (t.regionMatches(true, 0, VALIDATION + ":", 0, VALIDATION.length() + 1))
            return validation(t.substring(VALIDATION.length() + 1).trim());
        return new DoneWhen(t, "", "");
    }

    private static String text(JsonNode n, String field, String def) {
        if (!n.has(field) || n.get(field).isNull()) {
            return def;
        }
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
