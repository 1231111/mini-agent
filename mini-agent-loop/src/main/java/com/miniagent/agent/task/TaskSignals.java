package com.miniagent.agent.task;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 本轮从用户消息里直接观察到的事实。
 *
 * <p>每个字段都能在原文里指出对应证据（命中了哪条词表正则），不含任何
 * 「用户想干什么」的推断。因此它不会因为某一次判断失误而整体错位：
 * 命中的就是命中的，没命中就是没命中，可以逐字段复核。</p>
 *
 * <p>与它对应的旧结构是一个十一取值的意图枚举。枚举只能表达「本轮属于哪一类」，
 * 一旦选错，读这个枚举的所有下游会同时拿到错的输入；而这里每个字段各自独立，
 * 消费方各取所需，一处判断不适用不会牵连别处。</p>
 */
public record TaskSignals(
        boolean needsWeb,
        boolean needsFiles,
        boolean readsFile,
        boolean diagram,
        boolean pureImage,
        boolean imageIntoDoc,
        boolean simpleFile,
        boolean question,
        boolean complex,
        boolean taskAction,
        boolean continueTask,
        boolean publish
) {

    public static final TaskSignals NONE = new TaskSignals(
            false, false, false, false, false, false, false, false, false, false, false, false);

    /**
     * 命中了任何「要动手」的信号。
     *
     * <p>{@code readsFile} 不在其中：它只说「文本里有读取/分析这类动词」，
     * 读的对象可能是消息里自带的一段话（「帮我分析下这句话」），
     * 不必然要动工具。把它算进动手信号会把这类消息挤出轻问答轮。</p>
     *
     * <p>{@code taskAction} 在其中。它与 {@code question} 由构造互斥 ——
     * {@code questionIntent} / {@code factualQuestion} / {@code inMemoryTask}
     * 三条路径都在命中 {@code taskAction} 时提前返回 false，
     * 所以「命中动作词」与「是纯问答」不可能同时为真。
     * 正因如此，把 {@code taskAction} 计入这里不会改变 {@link #lightTurn()}；
     * 它实际影响的只有点评轮判定（见 {@code ContextBuildContext.reviewTurn}）——
     * 带图 +「帮我写个说明」是动手请求，不该被当成「只要求点评」。</p>
     */
    public boolean actionBearing() {
        return needsWeb || needsFiles || diagram || pureImage || imageIntoDoc
                || simpleFile || complex || taskAction || publish;
    }

    /**
     * 纯问答轮：问的是能力/寒暄/算术，且没有任何动手信号。
     *
     * <p>替代原先「意图等于问答」的判定。语义等价，但判据是文本事实而不是分类结果。
     * 用途是防止上一轮残留的子目标劫持本轮（例如「你好」被旧任务图接管）。</p>
     */
    public boolean lightTurn() {
        return question && !actionBearing();
    }

    /** 命中的信号名清单，写日志、轨迹与提示词用。 */
    public List<String> hits() {
        List<String> out = new ArrayList<>(12);
        if (needsWeb) {
            out.add("web");
        }
        if (needsFiles) {
            out.add("file");
        }
        if (readsFile) {
            out.add("read");
        }
        if (diagram) {
            out.add("diagram");
        }
        if (pureImage) {
            out.add("pureImage");
        }
        if (imageIntoDoc) {
            out.add("imageIntoDoc");
        }
        if (simpleFile) {
            out.add("simpleFile");
        }
        if (question) {
            out.add("question");
        }
        if (complex) {
            out.add("complex");
        }
        if (taskAction) {
            out.add("action");
        }
        if (continueTask) {
            out.add("continue");
        }
        if (publish) {
            out.add("publish");
        }
        return List.copyOf(out);
    }

    public String describe() {
        List<String> h = hits();
        return h.isEmpty() ? "none" : String.join(",", h);
    }

    /**
     * {@link #describe()} 的逆向：把信号名清单还原成对象。
     *
     * <p>存在的意义是让信号可以安全地落进日志、轨迹与提示词，再原样读回来 ——
     * 排查线上问题时能拿轨迹里那一行重建当轮信号，不必回放整轮对话。
     * 无法识别的名字忽略，因此新增信号不会让旧数据读不出来。</p>
     */
    public static TaskSignals parse(String described) {
        if (described == null || described.isBlank()) {
            return NONE;
        }
        boolean web = false;
        boolean file = false;
        boolean read = false;
        boolean diagram = false;
        boolean pureImage = false;
        boolean imageIntoDoc = false;
        boolean simpleFile = false;
        boolean question = false;
        boolean complex = false;
        boolean action = false;
        boolean cont = false;
        boolean publish = false;
        for (String raw : described.split(",")) {
            String name = raw.trim().toLowerCase(Locale.ROOT);
            switch (name) {
                case "web" -> web = true;
                case "file" -> file = true;
                case "read" -> read = true;
                case "diagram" -> diagram = true;
                case "pureimage" -> pureImage = true;
                case "imageintodoc" -> imageIntoDoc = true;
                case "simplefile" -> simpleFile = true;
                case "question" -> question = true;
                case "complex" -> complex = true;
                case "action" -> action = true;
                case "continue" -> cont = true;
                case "publish" -> publish = true;
                default -> { }
            }
        }
        return new TaskSignals(web, file, read, diagram, pureImage, imageIntoDoc,
                simpleFile, question, complex, action, cont, publish);
    }
}
