package com.miniagent.agent.web;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Trusted publication-date constraint from the user's request, never from model tool arguments.
 * The application binds it once per run; RunScope carries it to tools and delegated workers.
 */
public final class SearchDateContext {
    private static final ThreadLocal<Window> CURRENT = new ThreadLocal<>();
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Pattern TODAY = Pattern.compile("今日|今天|本日|当日|\\btoday(?:['’]s)?\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern YESTERDAY = Pattern.compile("昨日|昨天|\\byesterday(?:['’]s)?\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern PUBLICATION = Pattern.compile(
            "新闻|资讯|动态|报道|快讯|\\b(?:news|updates?|announcements?)\\b|"
                    + "(?:今日|今天|昨日|昨天).{0,20}(?:发布|公布|更新)的|"
                    + "\\b(?:published|released)\\s+(?:today|yesterday)\\b|"
                    + "\\b(?:today|yesterday)(?:['’]s)?\\b.{0,40}\\b(?:releases?|publications?)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern EXPLICIT_DATE = Pattern.compile(
            "(?<!\\d)(20\\d{2})[-/.年](\\d{1,2})[-/.月](\\d{1,2})日?(?!\\d)|(?<!\\d)(\\d{1,2})月(\\d{1,2})日(?!\\d)");
    // A competing date/range makes the request unsuitable for a single-day constraint.
    // In particular, "compare today with yesterday" must not silently lose yesterday.
    private static final Pattern OTHER_WINDOW = Pattern.compile(
            "20\\d{2}年|20\\d{2}[-/.年]\\d{1,2}|"
                    + "(?:近|过去|最近|前|未来)\\s*(?:\\d+|[一二两三四五六七八九十]+)\\s*(?:小时|天|日|周|月|年)|"
                    + "\\d+\\s*(?:小时|天内|hours?\\b|h\\b)|本周|这周|上周|本月|上月|前天|明天|去年|往年|历史|"
                    + "\\b(?:last|past|next)\\s+(?:\\d+|one|two|three)\\s+(?:hours?|days?|weeks?|months?|years?)\\b|"
                    + "\\b(?:this|last)\\s+(?:week|month|year)\\b|\\b(?:tomorrow|historical)\\b", Pattern.CASE_INSENSITIVE);

    private SearchDateContext() {}

    public record Window(LocalDate start, LocalDate end, ZoneId zone, Instant asOf) {
        public Window {
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(end, "end");
            Objects.requireNonNull(zone, "zone");
            Objects.requireNonNull(asOf, "asOf");
            if (start.isAfter(end)) {
                throw new IllegalArgumentException("Search window start must not exceed end");
            }
        }
    }

    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    public static Window current() {
        return CURRENT.get();
    }

    /** Used only by application/run-scope infrastructure, not exposed as a tool. */
    public static void set(Window window) {
        if (window == null) CURRENT.remove();
        else CURRENT.set(window);
    }

    public static Scope bind(Window window) {
        Window previous = current();
        set(window);
        return () -> set(previous);
    }

    public static ZoneId resolveZone(String configuredZone) {
        try {
            return ZoneId.of(configuredZone);
        } catch (Exception ignored) {
            return DEFAULT_ZONE;
        }
    }

    /**
     * Recognize unambiguous single-day publication requests. This is a date constraint,
     * not a task router. Mixed periods and historical comparisons remain unconstrained.
     * Only pass the user's own text, without attached documents or extracted page content.
     */
    public static Window fromRequest(String userText, Instant asOf, ZoneId zone) {
        if (userText == null || userText.isBlank()) return null;
        String text = userText;
        boolean today = TODAY.matcher(text).find();
        boolean yesterday = YESTERDAY.matcher(text).find();
        if (today == yesterday || !PUBLICATION.matcher(text).find()) {
            return null;
        }
        LocalDate day = asOf.atZone(zone).toLocalDate().minusDays(yesterday ? 1 : 0);
        Matcher dates = EXPLICIT_DATE.matcher(text);
        while (dates.find()) {
            try {
                LocalDate explicitDay = dates.group(1) == null
                        ? LocalDate.of(day.getYear(), Integer.parseInt(dates.group(4)), Integer.parseInt(dates.group(5)))
                        : LocalDate.of(Integer.parseInt(dates.group(1)), Integer.parseInt(dates.group(2)), Integer.parseInt(dates.group(3)));
                if (!day.equals(explicitDay)) return null;
            } catch (RuntimeException invalidDate) {
                return null;
            }
        }
        // Repeating today's actual date is equivalent, rather than a competing period.
        if (OTHER_WINDOW.matcher(dates.replaceAll(" ")).find()) return null;
        return new Window(day, day, zone, asOf);
    }
}
