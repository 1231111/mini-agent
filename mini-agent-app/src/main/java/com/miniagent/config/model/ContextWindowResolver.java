package com.miniagent.config.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/**
 * 从模型声明的多种窗口规格里选出**本次请求可用的输入窗口**。
 *
 * <p>对齐 WorkBuddy 的 {@code resolveEffectiveContextBudget}：它先按
 * {@code supportedLengths} 过滤出一个有序候选集，优先用 {@code defaultLength}
 * （必须落在候选集里），否则取候选集最小值，再退回 {@code maxInputTokens}。</p>
 *
 * <p>为什么不能只用「一个数字」：同一个模型常按套餐开放 128k / 200k / 1M 三档，
 * 声明成单值时要么按最小档压缩（浪费 8 倍空间），要么按最大档压缩（上游 400）。
 * 声明成列表后，选择规则与 WorkBuddy 一致，压缩点才不会建立在猜测上。</p>
 *
 * <p>与 WorkBuddy 的唯一差别：对方要求候选集至少两项才认
 * {@code defaultLength}，因为它拿的是模型目录、永远有多档；
 * 而 MiniAgent 的预设允许只声明 {@code context-window-tokens} 一个值，
 * 此时它就是权威值，不能因为「不够两项」被忽略，否则单窗口预设会退回「未知」。</p>
 */
public final class ContextWindowResolver {

    private ContextWindowResolver() {}

    /**
     * @param defaultLength     模型声明的默认输入窗口（{@code context-window-tokens}），0 = 未声明
     * @param supportedLengths  模型支持的窗口档位（{@code supported-context-windows}），可为空
     * @param maxInputTokens    厂商硬上限（{@code max-input-tokens}），0 = 未声明
     * @return 选中的窗口；全部未声明时返回 0，调用方回退到 {@code agent.context.max-tokens}
     */
    public static int resolve(int defaultLength, List<Integer> supportedLengths, int maxInputTokens) {
        List<Integer> supported = normalize(supportedLengths, maxInputTokens);
        if (defaultLength > 0 && supported.contains(defaultLength)) {
            return defaultLength;
        }
        if (!supported.isEmpty()) {
            return supported.get(0);
        }
        if (defaultLength > 0) {
            return defaultLength;
        }
        return Math.max(0, maxInputTokens);
    }

    /** 过滤非法值、去掉超过 {@code maxInputTokens} 的档位、去重并升序。 */
    static List<Integer> normalize(List<Integer> supportedLengths, int maxInputTokens) {
        if (Objects.isNull(supportedLengths) || supportedLengths.isEmpty()) {
            return List.of();
        }
        TreeSet<Integer> sorted = new TreeSet<>();
        for (Integer length : supportedLengths) {
            if (Objects.isNull(length) || length <= 0) {
                continue;
            }
            if (maxInputTokens > 0 && length > maxInputTokens) {
                continue;
            }
            sorted.add(length);
        }
        return new ArrayList<>(sorted);
    }
}
