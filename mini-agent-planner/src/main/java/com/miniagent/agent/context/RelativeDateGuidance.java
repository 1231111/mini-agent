package com.miniagent.agent.context;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/** Builds an explicit date anchor for requests that use relative dates such as "today". */
public final class RelativeDateGuidance {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    private RelativeDateGuidance() {
    }

    public static String render(ZonedDateTime now) {
        Objects.requireNonNull(now, "now");
        String today = now.toLocalDate().format(DATE);
        String zone = now.getZone().getId();
        return "【相对日期锚定】当前日期是 " + today + "，时区是 " + zone + "。"
                + "用户说今天/今日/昨天/本周/本月时，必须先按此时区换算为明确日期或日期范围；"
                + "搜索新闻或资料时用换算后的日期限定检索，并核对来源页面标注的发布日期，禁止把其他日期当作今天。";
    }
}
