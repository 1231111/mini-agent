package com.miniagent.agent.context;

import com.miniagent.agent.web.SearchDateContext;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/** Builds an explicit date anchor for requests that use relative dates such as "today". */
public final class RelativeDateGuidance {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ssXXX");

    private RelativeDateGuidance() {
    }

    /** Keep the original request's date anchor stable across midnight and replanning. */
    public static ZonedDateTime resolveNow(String configuredTimeZone) {
        SearchDateContext.Window window = SearchDateContext.current();
        return window == null
                ? ZonedDateTime.now(SearchDateContext.resolveZone(configuredTimeZone))
                : ZonedDateTime.ofInstant(window.asOf(), window.zone());
    }

    public static String render(ZonedDateTime now) {
        Objects.requireNonNull(now, "now");
        String today = now.toLocalDate().format(DATE);
        String zone = now.getZone().getId();
        String startOfToday = now.toLocalDate().atStartOfDay(now.getZone()).format(TIMESTAMP);
        return "【相对日期锚定】当前日期是 " + today + "，时区是 " + zone + "。"
                + "用户说今天/今日/昨天/本周/本月时，必须先按此时区换算为明确日期或日期范围；"
                + "搜索新闻或资料时用换算后的日期限定检索，并核对来源页面标注的发布日期，禁止把其他日期当作今天。\n"
                + "用户要求今天/今日/today 时，范围严格为用户时区当日零点 " + startOfToday
                + " 至当前查询时刻 " + now.format(TIMESTAMP) + "（as-of），不含未来内容。"
                + "web_search 的 dateFrom=" + today + " 且 dateTo=" + today + "；"
                + "禁止擅自扩大为最近24/48小时、过去两天或加入昨天内容凑数。"
                + "最新/latest 只在用户指定日期范围内取最新，不能覆盖今天/今日的限制。"
                + "当日可核验新闻不足时如实说明，不得用日期不明、昨日或更早的新闻冒充；"
                + "需要更大日期范围须由用户明确要求。";
    }
}
