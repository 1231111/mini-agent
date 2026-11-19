package com.miniagent.agent.planner;

import com.fasterxml.jackson.databind.JsonNode;
import com.miniagent.agent.tool.CapabilityRegistry;
import com.miniagent.agent.todo.AcceptanceEvidenceFiles;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import org.apache.commons.lang3.StringUtils;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从 todo evidence / 本步工具结果 / file path 抽出契约产出。
 * 不用整轮模型散文当 SUCCESS 证据。
 */
final class NodeOutputBinder {

    private static final int MAX_VERIFICATION_EVIDENCE_CHARS = 12_000;
    private static final int MAX_VERIFICATION_RECEIPT_CHARS = 2_000;
    private static final Pattern READ_FILE_RANGE = Pattern.compile(
            "(?i)\\[read_file path=(.*?) line_offset=(\\d+) char_offset=(\\d+)");

    // Delivery links are emitted as ordinary ASCII URLs in browser receipts.
    // Stop before adjacent CJK prose/punctuation so the named binding remains
    // directly clickable instead of containing the surrounding sentence.
    private static final Pattern URL = Pattern.compile(
            "https?://[A-Za-z0-9._~:/?#\\[\\]@!$&'()*+,;=%-]+");

    private NodeOutputBinder() {}

    record Bound(String evidence, Map<String, String> bindings) {
        Bound {
            evidence = evidence == null ? "" : evidence;
            bindings = bindings == null ? Map.of() : Map.copyOf(bindings);
        }

        boolean complete(TaskNode node) {
            if (node == null) {
                return false;
            }
            if (node.doneWhen() != null && node.doneWhen().isFile()
                    && AcceptanceEvidenceFiles.resolveDeclaredFile(node.doneWhen().path()) == null) return false;
            if (node.outputs() == null || node.outputs().isEmpty()) {
                return StringUtils.isNotBlank(evidence);
            }
            for (String o : node.outputs()) {
                if (StringUtils.isBlank(bindings.get(o))) {
                    return false;
                }
            }
            return true;
        }
    }

    static Bound ofNode(TaskNode node) {
        if (node == null) {
            return new Bound("", Map.of());
        }
        return new Bound(node.output(), node.outputBindings());
    }

    static Bound bind(TaskNode node, String todoEvidence,
                      List<ChatMessage> messages, String judgeAnswer) {
        return bind(node, todoEvidence, messages, judgeAnswer, null);
    }

    static Bound bind(TaskNode node, String todoEvidence, List<ChatMessage> messages,
                      String judgeAnswer, com.miniagent.agent.tool.ToolResult structuredResult) {
        return bind(node, todoEvidence, messages, judgeAnswer, structuredResult, "");
    }

    /**
     * Binds a node's own execution evidence and, when the node is a delivery
     * step, the already committed evidence produced by its dependencies.
     *
     * Delivery nodes commonly only carry a {@code todo.submit} bookkeeping
     * call.  That receipt does not contain the browser/file evidence which was
     * produced by the preceding validation node, so binding only the current
     * turn incorrectly reports "declared outputs 未绑定".  The scheduler passes
     * the dependency evidence explicitly to keep this helper independent from
     * the graph store and to avoid treating stale predecessor output as proof
     * for ordinary action or validation nodes.
     */
    static Bound bind(TaskNode node, String todoEvidence, List<ChatMessage> messages,
                      String judgeAnswer, com.miniagent.agent.tool.ToolResult structuredResult,
                      String predecessorEvidence) {
        if (node == null) {
            return new Bound("", Map.of());
        }
        String todo = usable(todoEvidence);
        if (structuredResult != null && !structuredResult.isSuccess()) {
            return new Bound(structuredResult.legacyText(), Map.of());
        }
        DoneWhen dw = node.doneWhen() == null ? DoneWhen.note() : node.doneWhen();
        // A DAG deliver node may intentionally have no action tool: its only
        // operation is todo.submit, which hands the evidence back to the
        // scheduler.  Todo receipts are bookkeeping and must stay excluded for
        // synthesis/judge nodes, but the deliver node's declared output is the
        // submitted artifact itself (for example a URL).  Preserve that value
        // as the node evidence so a submit-only proposal cannot be mistaken for
        // an unbound/failed execution.
        if (isSubmitOnlyDelivery(node)) {
            String todoFailure = lastTodoFailure(messages);
            if (!todoFailure.isEmpty()) {
                // A failed submit is terminal for this delivery proposal. Do
                // not let predecessor evidence satisfy declared outputs after
                // the current submission was rejected.
                return new Bound(todoFailure, Map.of());
            }
        }
        if (todo.isEmpty() && isSubmitOnlyDelivery(node)) {
            todo = lastTodoEvidence(messages);
        }
        String tools = structuredResult == null
                ? lastUsefulToolText(messages) : usable(structuredResult.legacyText());
        if (structuredResult == null && StepEvaluator.looksLikeToolError(tools)) {
            // Do not let an old file path or earlier successful tool hide a failed
            // final action. The evaluator must see the failure before world checks.
            return new Bound(tools, Map.of());
        }
        if (dw.isJudge() || dw.isValidation() || dw.isCommand()) {
            // A verification spans observations and actions. The final receipt
            // alone (often just todo.update or a reset click) loses that proof.
            String history = verificationToolEvidence(messages);
            if (StringUtils.isNotBlank(history)) tools = history;
        }
        String predecessor = isSubmitOnlyDelivery(node) ? usable(predecessorEvidence) : "";
        // Command acceptance must persist the actual command tool result for
        // graph re-evaluation. Model-submitted todo text is never its proof.
        String evidence = dw.isCommand() ? tools : firstNonBlank(todo, tools);
        if (StringUtils.isBlank(evidence)) {
            evidence = predecessor;
        }
        Map<String, String> bindings = new LinkedHashMap<>();
        mergeJsonPorts(bindings, node, todo);
        mergeJsonPorts(bindings, node, tools);
        mergeFilePath(bindings, node);
        if (dw.isFile() && StringUtils.isBlank(evidence)
                && StringUtils.isNotBlank(dw.path())) {
            evidence = dw.path();
        }
        if (dw.isJudge()) {
            String ans = usable(judgeAnswer);
            if (StringUtils.isBlank(evidence)) {
                evidence = ans;
            }
            fillMissing(bindings, node, ans);
        }
        fillPredecessorBindings(bindings, node, predecessor);
        fillMissing(bindings, node, evidence);
        if (StringUtils.isBlank(evidence) && !bindings.isEmpty()) {
            evidence = firstBinding(bindings, node);
        }
        return new Bound(evidence, bindings);
    }

    private static boolean isSubmitOnlyDelivery(TaskNode node) {
        if (node == null || !"deliver".equalsIgnoreCase(node.capability())) {
            return false;
        }
        DoneWhen dw = node.doneWhen() == null ? DoneWhen.note() : node.doneWhen();
        return dw.isNote() && node.outputs() != null && !node.outputs().isEmpty();
    }

    /** Extract only the actual evidence field from the latest todo receipt. */
    private static String lastTodoEvidence(List<ChatMessage> messages) {
        if (messages == null) return "";
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if (!(message instanceof ToolExecutionResultMessage result)
                    || !CapabilityRegistry.FALLBACK_TOOL.equals(result.toolName())) {
                continue;
            }
            JsonNode root = parseObject(result.text());
            if (root == null) continue;
            boolean success = root.path("success").asBoolean(false);
            String action = root.path("action").asText("").trim().toLowerCase(java.util.Locale.ROOT);
            if (!success) {
                // A failed submit is the terminal receipt for this proposal.
                // Do not walk back to an older successful submit and let it hide
                // the latest failure (the same stale-evidence rule used for
                // ordinary action tool results).
                return usable(result.text());
            }
            if (!"submit".equals(action) && !"update".equals(action)) {
                // list/set/restore are bookkeeping only; an earlier submit in
                // the same proposal can still be the current deliver proof.
                continue;
            }
            String evidence = usable(root.path("evidence").asText(""));
            if (!evidence.isEmpty()) return evidence;
            // Compatibility with receipts written before the top-level field was
            // added: select the most recently in-progress item, which is the
            // node whose submit was just accepted. Completed upstream items are
            // deliberately ignored so a stale URL cannot bind this node.
            JsonNode items = root.path("items");
            if (items.isArray()) {
                for (int j = items.size() - 1; j >= 0; j--) {
                    JsonNode item = items.get(j);
                    if (!"in_progress".equalsIgnoreCase(item.path("status").asText(""))) continue;
                    evidence = usable(item.path("evidence").asText(""));
                    if (!evidence.isEmpty()) return evidence;
                }
            }
        }
        return "";
    }

    /** Return the newest failed todo submit/update receipt, if any. */
    private static String lastTodoFailure(List<ChatMessage> messages) {
        if (messages == null) return "";
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if (!(message instanceof ToolExecutionResultMessage result)
                    || !CapabilityRegistry.FALLBACK_TOOL.equals(result.toolName())) {
                continue;
            }
            JsonNode root = parseObject(result.text());
            if (root == null) continue;
            String action = root.path("action").asText("").trim().toLowerCase(java.util.Locale.ROOT);
            if (!root.path("success").asBoolean(false)) return usable(result.text());
            if (!"submit".equals(action) && !"update".equals(action)) continue;
            // The newest submit/update succeeded, so an older failed receipt
            // must not poison this delivery attempt.
            return "";
        }
        return "";
    }

    static String verificationToolEvidence(List<ChatMessage> messages) {
        if (messages == null) return "";
        List<ToolExecutionResultMessage> receipts = new ArrayList<>();
        Set<String> seenReceipts = new HashSet<>();
        for (ChatMessage message : messages) {
            if (!(message instanceof ToolExecutionResultMessage result)
                    || CapabilityRegistry.FALLBACK_TOOL.equals(result.toolName())
                    || StringUtils.isBlank(usable(result.text()))) continue;
            // Repeated identical reads are retry noise, not additional evidence.
            if (seenReceipts.add(result.toolName() + "\n" + result.text())) receipts.add(result);
        }
        if (receipts.isEmpty()) return "";

        // Keep semantically important source receipts even after many exploratory
        // calls. A fixed first+last window silently discarded the successful
        // read of BENCHMARK_TASK.md once retries filled the tail with repeated
        // shader snippets, which made an otherwise valid repair fail closed.
        List<Integer> selected = new ArrayList<>();
        boolean[] included = new boolean[receipts.size()];
        selectBestReceipt(receipts, included, selected, "benchmark_task.md");
        selectBestReceipt(receipts, included, selected, "game_context.md");
        selectHtmlRanges(receipts, included, selected);
        addReceiptIndex(included, selected, 0);
        for (int i = Math.max(0, receipts.size() - 4); i < receipts.size(); i++) {
            addReceiptIndex(included, selected, i);
        }

        // Fill remaining space with the best distinct receipts, not merely the
        // last N calls. This retains command exit codes and other evidence while
        // bounding the judge payload.
        List<Integer> remaining = new ArrayList<>();
        for (int i = 0; i < receipts.size(); i++) if (!included[i]) remaining.add(i);
        remaining.sort(java.util.Comparator
                .comparingInt((Integer i) -> verificationReceiptPriority(receipts.get(i).text())).reversed()
                .thenComparingInt(Integer::intValue));
        for (int index : remaining) addReceiptIndex(included, selected, index);
        selected.sort(java.util.Comparator
                .comparingInt((Integer i) -> verificationReceiptPriority(receipts.get(i).text())).reversed()
                .thenComparingInt(Integer::intValue));

        StringBuilder evidence = new StringBuilder("本节点工具执行记录（按来源优先级整理；保留关键文件和不同 HTML 范围；单条长回执会明确标记截断）：\n");
        for (int index : selected) {
            var result = receipts.get(index);
            String text = compactVerificationReceipt(result.text(), MAX_VERIFICATION_RECEIPT_CHARS);
            String section = "\n[receipt=" + index + " tool=" + result.toolName() + "]\n" + text + "\n";
            int remainingChars = MAX_VERIFICATION_EVIDENCE_CHARS - evidence.length();
            if (remainingChars <= 0) break;
            if (section.length() > remainingChars) {
                if (remainingChars <= 80) break;
                section = section.substring(0, remainingChars - 40)
                        + "\n[本节点验收证据总量达到上限；后续回执未附加]\n";
            }
            evidence.append(section);
        }
        return evidence.toString();
    }

    private static void selectBestReceipt(List<ToolExecutionResultMessage> receipts, boolean[] included,
                                          List<Integer> selected, String marker) {
        for (int i = 0; i < receipts.size(); i++) {
            String text = receipts.get(i).text();
            if (text.toLowerCase(java.util.Locale.ROOT).contains(marker)) {
                addReceiptIndex(included, selected, i);
                return;
            }
        }
    }

    /** Preserve up to four distinct source ranges from large HTML/JS artifacts. */
    private static void selectHtmlRanges(List<ToolExecutionResultMessage> receipts, boolean[] included,
                                         List<Integer> selected) {
        List<Integer> html = new ArrayList<>();
        java.util.Set<String> ranges = new java.util.LinkedHashSet<>();
        for (int i = 0; i < receipts.size(); i++) {
            String text = receipts.get(i).text();
            if (!text.toLowerCase(java.util.Locale.ROOT).contains("index.html")
                    && !text.toLowerCase(java.util.Locale.ROOT).contains(".html")) continue;
            Matcher matcher = READ_FILE_RANGE.matcher(text);
            String range = matcher.find()
                    ? matcher.group(1).toLowerCase(java.util.Locale.ROOT) + ":" + matcher.group(2) + ":" + matcher.group(3)
                    : "receipt:" + i;
            if (ranges.add(range)) html.add(i);
        }
        if (html.size() <= 4) {
            html.forEach(i -> addReceiptIndex(included, selected, i));
            return;
        }
        for (int position : List.of(0, html.size() / 3, (2 * html.size()) / 3, html.size() - 1)) {
            addReceiptIndex(included, selected, html.get(position));
        }
    }

    private static void addReceiptIndex(boolean[] included, List<Integer> selected, int index) {
        if (index >= 0 && index < included.length && !included[index]) {
            included[index] = true;
            selected.add(index);
        }
    }

    private static int verificationReceiptPriority(String text) {
        String lower = StringUtils.defaultString(text).toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("benchmark_task.md")) return 100;
        if (lower.contains("game_context.md")) return 95;
        if (lower.contains("index.html") || lower.contains(".html")) return 85;
        if (lower.contains("exit_code=")) return 75;
        if (lower.contains("read_file path=")) return 65;
        return 10;
    }

    private static String compactVerificationReceipt(String text, int maxChars) {
        if (text.length() <= maxChars) return text;
        int head = maxChars * 2 / 3;
        int tail = maxChars - head;
        return text.substring(0, head) + "\n[本条中段已截断；保留首尾证据]\n"
                + text.substring(text.length() - tail);
    }

    private static String usable(String s) {
        if (StringUtils.isBlank(s) || StepEvaluator.isHollowEvidence(s)) {
            return "";
        }
        return s.trim();
    }

    private static String firstNonBlank(String a, String b) {
        if (StringUtils.isNotBlank(a)) {
            return a;
        }
        return b == null ? "" : b;
    }

    private static String lastUsefulToolText(List<ChatMessage> messages) {
        String last = "";
        if (messages == null) {
            return last;
        }
        for (ChatMessage m : messages) {
            if (!(m instanceof ToolExecutionResultMessage tr)) {
                continue;
            }
            if (CapabilityRegistry.FALLBACK_TOOL.equals(tr.toolName())) {
                continue;
            }
            String t = usable(tr.text());
            if (t.isEmpty()) {
                continue;
            }
            last = t;
        }
        return last;
    }

    private static void mergeJsonPorts(Map<String, String> bindings,
                                       TaskNode node, String json) {
        if (node == null || StringUtils.isBlank(json) || node.outputs().isEmpty()) {
            return;
        }
        JsonNode root = parseObject(json);
        if (root == null) {
            return;
        }
        JsonNode bag = portBag(root);
        for (String declared : node.outputs()) {
            JsonNode value = bag.get(declared);
            if (value == null || value.isNull()) {
                continue;
            }
            if (StringUtils.isNotBlank(bindings.get(declared))) {
                continue;
            }
            bindings.put(declared,
                    value.isValueNode() ? value.asText() : value.toString());
        }
        JsonNode path = root.get("path");
        if (path != null && path.isValueNode()
                && StringUtils.isNotBlank(path.asText())) {
            mergePath(bindings, node, path.asText());
        }
    }

    private static JsonNode portBag(JsonNode root) {
        if (root.has("outputs") && root.get("outputs").isObject()) {
            return root.get("outputs");
        }
        if (root.has("artifacts") && root.get("artifacts").isObject()) {
            return root.get("artifacts");
        }
        return root;
    }

    private static void mergeFilePath(Map<String, String> bindings, TaskNode node) {
        DoneWhen dw = node.doneWhen();
        if (dw == null || !dw.isFile() || StringUtils.isBlank(dw.path())) {
            return;
        }
        mergePath(bindings, node, dw.path());
    }

    private static void mergePath(Map<String, String> bindings,
                                  TaskNode node, String path) {
        if (StringUtils.isBlank(path) || node.outputs().isEmpty()) {
            return;
        }
        String norm = path.replace('\\', '/');
        for (String o : node.outputs()) {
            if (StringUtils.isNotBlank(bindings.get(o))) {
                continue;
            }
            bindings.put(o, norm);
        }
    }

    private static void fillMissing(Map<String, String> bindings,
                                    TaskNode node, String value) {
        if (StringUtils.isBlank(value) || node.outputs().isEmpty()) {
            return;
        }
        for (String o : node.outputs()) {
            if (StringUtils.isBlank(bindings.get(o))) {
                bindings.put(o, value);
            }
        }
    }

    /**
     * Delivery outputs are named contracts.  Keep a link output usable by the
     * client instead of binding the entire browser diagnostic transcript to it.
     * Other output names intentionally retain the complete predecessor proof.
     */
    private static void fillPredecessorBindings(Map<String, String> bindings,
                                                TaskNode node, String predecessor) {
        if (node == null || StringUtils.isBlank(predecessor) || node.outputs().isEmpty()) {
            return;
        }
        for (String output : node.outputs()) {
            if (StringUtils.isNotBlank(bindings.get(output))) {
                continue;
            }
            String value = isLinkOutput(output) ? firstUrl(predecessor) : predecessor;
            if (StringUtils.isNotBlank(value)) {
                bindings.put(output, value);
            }
        }
    }

    private static boolean isLinkOutput(String output) {
        if (StringUtils.isBlank(output)) return false;
        String normalized = output.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("url") || normalized.contains("link")
                || output.contains("链接") || output.contains("地址");
    }

    private static String firstUrl(String text) {
        Matcher matcher = URL.matcher(text == null ? "" : text);
        if (!matcher.find()) return "";
        String value = matcher.group();
        while (!value.isEmpty() && isUrlTrailingPunctuation(value.charAt(value.length() - 1))) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static boolean isUrlTrailingPunctuation(char c) {
        return ".,!?;:)]}".indexOf(c) >= 0
                || switch (c) {
                    case '\uFF0C', '\u3002', '\uFF1B', '\uFF1A', '\u3001',
                            '\u3009', '\uFF09', '\u3011', '\u300D' -> true;
                    default -> false;
                };
    }

    private static String firstBinding(Map<String, String> bindings, TaskNode node) {
        for (String o : node.outputs()) {
            String v = bindings.get(o);
            if (StringUtils.isNotBlank(v)) {
                return v;
            }
        }
        return "";
    }

    private static JsonNode parseObject(String json) {
        try {
            JsonNode root = PlannerStateJson.MAPPER.readTree(json);
            if (root != null && root.isObject()) {
                return root;
            }
            return null;
        } catch (Exception ignored) {
            return null;
        }
    }
}
