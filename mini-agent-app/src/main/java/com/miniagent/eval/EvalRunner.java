package com.miniagent.eval;

import org.springframework.beans.factory.annotation.Autowired;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.application.AgentChatApplicationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.Objects;
import java.util.Optional;

/**
 * 评估 Runner（对标 LLM-agent 工程常见的 eval harness）。
 *
 * 触发方式：启动应用时带 {@code --eval} 参数才运行，例如：
 *   mvn -pl mini-agent-app spring-boot:run -Dspring-boot.run.arguments=--eval
 *   或  java -jar app.jar --eval --eval.dir=eval-cases --eval.category=tool
 * 不带 --eval 时本类直接返回，对正常启动零影响。
 *
 * 流程：扫描 eval-cases/*.json → 逐条调 AgentChatApplicationService.chat() →
 *       用 EvalChecker 跑断言 → 按 category 维度汇总通过率 → 打印 + 落盘 eval-report.json。
 *
 * 设计取舍：
 *   - 用例隔离：每条用独立 sessionId，避免历史串扰；
 *   - 只断言可观测副作用（回复 + 文件），与 Agent 内部实现解耦；
 *   - 超时保护：单用例默认 180s，避免个别用例卡死整批。
 */
@Slf4j
@Component
@Order(100)
public class EvalRunner implements CommandLineRunner {

    @Autowired
    private AgentChatApplicationService chatService;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long DEFAULT_TIMEOUT_MS = 180_000;
    private static final Long EVAL_USER_ID = 0L; // 评估专用用户

    @Override
    public void run(String... args) {
        List<String> argList = List.of(args);
        if (!argList.contains("--eval")) return; // 未显式要求评估则跳过

        Path projectRoot = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        String dir = argValue(argList, "--eval.dir", "eval-cases");
        String onlyCategory = argValue(argList, "--eval.category", null);
        Path caseDir = projectRoot.resolve(dir);

        log.info("===== 评估开始 dir={} category={} =====", caseDir, Optional.ofNullable(onlyCategory).orElse("全部"));

        List<EvalCase> cases = loadCases(caseDir, onlyCategory);
        if (cases.isEmpty()) {
            log.warn("未找到用例（dir={}）。请确认 eval-cases 目录存在且含 *.json。", caseDir);
            return;
        }

        EvalChecker checker = new EvalChecker(projectRoot);
        List<CaseResult> results = new ArrayList<>();
        for (EvalCase ec : cases) {
            results.add(runOne(ec, checker));
        }

        EvalReport report = aggregate(results);
        printReport(report, results);
        writeReport(projectRoot, report, results);
        log.info("===== 评估结束：{}/{} 用例通过（{}%）=====",
                report.passedCases, report.totalCases, pct(report.passedCases, report.totalCases));
    }

    private CaseResult runOne(EvalCase ec, EvalChecker checker) {
        String sessionId = "eval_" + ec.id + "_" + UUID.randomUUID().toString().substring(0, 6);
        long timeout = ec.timeoutMs > 0 ? ec.timeoutMs : DEFAULT_TIMEOUT_MS;
        long t0 = System.currentTimeMillis();
        String response;
        String runtimeError = null;
        try {
            CompletableFuture<String> fut = CompletableFuture.supplyAsync(
                    () -> chatService.chat(EVAL_USER_ID, sessionId, ec.prompt));
            response = fut.get(timeout, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            response = "";
            runtimeError = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        long elapsed = System.currentTimeMillis() - t0;

        CaseResult cr = new CaseResult();
        cr.id = ec.id;
        cr.category = Optional.ofNullable(ec.category).orElse("uncategorized");
        cr.elapsedMs = elapsed;
        cr.runtimeError = runtimeError;
        cr.checkResults = new ArrayList<>();

        boolean allPass = Objects.isNull(runtimeError);
        if (Objects.nonNull(ec.checks)) {
            for (EvalCheck check : ec.checks) {
                CheckResult r = checker.check(check, response);
                cr.checkResults.add(r);
                if (!r.pass) allPass = false;
            }
        }
        cr.pass = allPass;
        log.info("[{}] {} ({}ms){}", cr.pass ? "PASS" : "FAIL", ec.id, elapsed,
                Objects.nonNull(runtimeError) ? " 运行错误: " + runtimeError : "");
        return cr;
    }

    private List<EvalCase> loadCases(Path caseDir, String onlyCategory) {
        List<EvalCase> cases = new ArrayList<>();
        if (!Files.isDirectory(caseDir)) return cases;
        try (var stream = Files.list(caseDir)) {
            var files = stream.filter(p -> p.toString().endsWith(".json")).sorted().toList();
            for (Path f : files) {
                try {
                    EvalCase ec = MAPPER.readValue(Files.readString(f), EvalCase.class);
                    if (Objects.isNull(ec.id) || Objects.isNull(ec.prompt)) {
                        log.warn("跳过无效用例（缺 id/prompt）: {}", f.getFileName());
                        continue;
                    }
                    if (Objects.isNull(onlyCategory) || onlyCategory.equalsIgnoreCase(ec.category)) {
                        cases.add(ec);
                    }
                } catch (Exception e) {
                    log.warn("解析用例失败 {}: {}", f.getFileName(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("扫描用例目录失败: {}", e.getMessage());
        }
        return cases;
    }

    private EvalReport aggregate(List<CaseResult> results) {
        EvalReport report = new EvalReport();
        report.totalCases = results.size();
        report.byCategory = new LinkedHashMap<>();
        for (CaseResult cr : results) {
            if (cr.pass) report.passedCases++;
            var dim = report.byCategory.computeIfAbsent(cr.category, k -> new EvalReport.Dimension());
            dim.total++;
            if (cr.pass) dim.passed++;
        }
        return report;
    }

    private void printReport(EvalReport report, List<CaseResult> results) {
        StringBuilder sb = new StringBuilder("\n========== 评估报告 ==========\n");
        sb.append(String.format("总用例: %d  通过: %d  通过率: %s%%%n",
                report.totalCases, report.passedCases, pct(report.passedCases, report.totalCases)));
        sb.append("---- 分维度通过率 ----\n");
        report.byCategory.forEach((cat, d) ->
                sb.append(String.format("  %-12s %d/%d  (%s%%)%n", cat, d.passed, d.total, pct(d.passed, d.total))));
        sb.append("---- 失败用例 ----\n");
        boolean anyFail = false;
        for (CaseResult cr : results) {
            if (cr.pass) continue;
            anyFail = true;
            sb.append("  ✗ ").append(cr.id);
            if (Objects.nonNull(cr.runtimeError)) sb.append("  运行错误: ").append(cr.runtimeError);
            sb.append('\n');
            for (CheckResult c : cr.checkResults) {
                if (!c.pass) sb.append("      - ").append(c.description)
                        .append("  原因: ").append(c.reason).append('\n');
            }
        }
        if (!anyFail) sb.append("  （无）\n");
        sb.append("==============================");
        log.info(sb.toString());
    }

    private void writeReport(Path projectRoot, EvalReport report, List<CaseResult> results) {
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("summary", report);
            out.put("cases", results);
            Path target = projectRoot.resolve("eval-report.json");
            Files.writeString(target, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(out));
            log.info("报告已写入: {}", target);
        } catch (Exception e) {
            log.warn("写报告失败: {}", e.getMessage());
        }
    }

    private static String pct(int n, int total) {
        return total == 0 ? "0.0" : String.format("%.1f", n * 100.0 / total);
    }

    private static String argValue(List<String> args, String key, String def) {
        for (String a : args) {
            if (a.startsWith(key + "=")) return a.substring(key.length() + 1);
        }
        return def;
    }
}
