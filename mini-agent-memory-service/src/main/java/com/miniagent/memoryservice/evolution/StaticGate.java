package com.miniagent.memoryservice.evolution;

import com.miniagent.memory.SecurityScanner;
import com.miniagent.memory.contract.MemoryApi.ScopeType;
import com.miniagent.memory.contract.ProcedureContent;
import com.miniagent.memoryservice.config.EvolutionProperties;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 候选上线前的确定性检查（设计文档 §7.2）：结构与长度、非空改动、未被否决过、安全扫描、
 * 不引入新 URL、改动幅度（LCS）、证据引用与防投毒。任何一条不过就不进评估。
 * 未实现：步骤里的工具名校验（规划器尚无可用工具清单接口）、同一基线的第二个候选。
 */
@Component
public class StaticGate {
    private static final Pattern URL = Pattern.compile("https?://[^\\s)\"'<>]+");
    /** 替换一个步骤 = 删 1 + 增 1；再短的 SOP 也允许改一步，比例上限只约束更大的改动。 */
    private static final int SINGLE_STEP_REPLACEMENT = 2;

    public record Result(boolean passed, String reason) {
        static final Result OK = new Result(true, null);

        static Result fail(String reason) {
            return new Result(false, reason);
        }
    }

    /** 本次提供给生成器的证据；requireDiverse=false 时（重验掉分的回放案例）不做会话 / 成员校验。 */
    public record Evidence(Map<String, EvidenceCases.Case> provided, boolean requireDiverse) {
    }

    private final EvolutionProperties props;

    public StaticGate(EvolutionProperties props) {
        this.props = props;
    }

    public Result check(ScopeType scope, ProcedureContent base, ProcedureContent candidate,
                        List<String> evidenceRefs, Evidence evidence, Set<String> rejectedHashes) {
        Result structure = structure(base, candidate);
        if (!structure.passed()) {
            return structure;
        }
        String hash = candidate.hash();
        if (hash.equals(base.hash())) {
            return Result.fail("候选与基线相同");
        }
        if (rejectedHashes.contains(hash)) {
            return Result.fail("候选与曾被否决或回滚的版本相同");
        }
        if (SecurityScanner.scan(candidate.text()) != null) {
            return Result.fail("安全扫描未通过");
        }
        Set<String> newUrls = urls(candidate.text());
        newUrls.removeAll(urls(base.text()));
        if (!newUrls.isEmpty()) {
            return Result.fail("引入了基线没有的 URL");
        }
        double ratio = changeRatio(base.steps(), candidate.steps());
        long changed = Math.round(ratio * (base.steps().size() + candidate.steps().size()));
        if (changed > SINGLE_STEP_REPLACEMENT && ratio > props.getGate().getMaxStepChangeRatio()) {
            return Result.fail(String.format("步骤改动比例 %.2f 超过上限", ratio));
        }
        return evidence(scope, evidenceRefs, evidence);
    }

    private Result structure(ProcedureContent base, ProcedureContent candidate) {
        EvolutionProperties.Gate gate = props.getGate();
        int steps = candidate.steps().size();
        if (steps == 0 || steps > gate.getMaxSteps()
                || steps > base.steps().size() + gate.getMaxAddedSteps()) {
            return Result.fail("步骤数为 0、超过 " + gate.getMaxSteps() + " 或比基线多出 "
                    + gate.getMaxAddedSteps() + " 步以上");
        }
        if (candidate.description().length() > gate.getMaxDescriptionChars()) {
            return Result.fail("描述过长");
        }
        if (candidate.preconditions().size() > gate.getMaxListItems()
                || candidate.successConditions().size() > gate.getMaxListItems()) {
            return Result.fail("前置条件或成功条件过多");
        }
        for (List<String> list : List.of(candidate.preconditions(), candidate.steps(),
                candidate.successConditions())) {
            if (list.stream().anyMatch(s -> s.length() > gate.getMaxItemChars())) {
                return Result.fail("单条内容过长");
            }
        }
        return Result.OK;
    }

    /** 每处修改要有证据：引用非空且都在本次提供的证据里；来自 ≥2 个会话，PROJECT 再要求 ≥2 名成员。 */
    private Result evidence(ScopeType scope, List<String> refs, Evidence evidence) {
        if (evidence.provided().isEmpty()) {
            return Result.OK;
        }
        if (refs == null || refs.isEmpty() || !evidence.provided().keySet().containsAll(refs)) {
            return Result.fail("未引用或引用了不存在的证据");
        }
        if (!evidence.requireDiverse()) {
            return Result.OK;
        }
        List<EvidenceCases.Case> cited = refs.stream().map(evidence.provided()::get).toList();
        long sessions = cited.stream().map(EvidenceCases.Case::session)
                .filter(Objects::nonNull).distinct().count();
        if (sessions < props.getGate().getMinEvidenceSessions()) {
            return Result.fail("引用的证据来自不足 " + props.getGate().getMinEvidenceSessions()
                    + " 个会话");
        }
        long members = cited.stream().map(EvidenceCases.Case::userId)
                .filter(Objects::nonNull).distinct().count();
        if (scope == ScopeType.PROJECT && members < props.getGate().getMinProjectMembers()) {
            return Result.fail("项目 SOP 的证据来自不足 " + props.getGate().getMinProjectMembers()
                    + " 名成员");
        }
        return Result.OK;
    }

    /** (删去的 + 新增的) / (两边步骤总数)，按最长公共子序列对齐。 */
    static double changeRatio(List<String> base, List<String> candidate) {
        int total = base.size() + candidate.size();
        if (total == 0) {
            return 0;
        }
        int[][] lcs = new int[base.size() + 1][candidate.size() + 1];
        for (int i = 1; i <= base.size(); i++) {
            for (int j = 1; j <= candidate.size(); j++) {
                lcs[i][j] = base.get(i - 1).equals(candidate.get(j - 1)) ? lcs[i - 1][j - 1] + 1
                        : Math.max(lcs[i - 1][j], lcs[i][j - 1]);
            }
        }
        int common = lcs[base.size()][candidate.size()];
        return (double) (total - 2 * common) / total;
    }

    private static Set<String> urls(String text) {
        Set<String> urls = new HashSet<>();
        Matcher matcher = URL.matcher(text);
        while (matcher.find()) {
            urls.add(matcher.group());
        }
        return urls;
    }
}
