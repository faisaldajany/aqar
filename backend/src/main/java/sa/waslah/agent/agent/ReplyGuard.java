package sa.waslah.agent.agent;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic checks on the model's final reply before it reaches a customer. The prompt asks
 * for the same rules; this catches the cases where the model ignores them.
 */
public final class ReplyGuard {

    public record Verdict(boolean ok, String reason) {
        static final Verdict OK = new Verdict(true, null);
    }

    static final int MAX_CHARS = 1200;

    /** Ad license numbers on Saudi listings are 10 digits starting with 7. Arabic-Indic digits are normalised first. */
    private static final Pattern LICENSE = Pattern.compile("(?<!\\d)7\\d{9}(?!\\d)");

    private ReplyGuard() {
    }

    /**
     * @param reply            the model's text
     * @param allowedLicenses  licenses returned by search in this run plus those already sent in this conversation
     */
    public static Verdict check(String reply, Collection<String> allowedLicenses) {
        if (reply == null || reply.isBlank()) {
            return new Verdict(false, "empty");
        }
        if (reply.length() > MAX_CHARS) {
            return new Verdict(false, "too_long");
        }
        Set<String> allowed = new HashSet<>(allowedLicenses);
        Matcher m = LICENSE.matcher(normalizeDigits(reply));
        while (m.find()) {
            if (!allowed.contains(m.group())) {
                return new Verdict(false, "unknown_license:" + m.group());
            }
        }
        return Verdict.OK;
    }

    private static final Pattern AVAILABILITY = Pattern.compile(
            "متوفر|متاح|عندنا|لدينا|موجود|ما فيه|لا يوجد|we have|available", Pattern.CASE_INSENSITIVE);
    private static final Pattern PROPERTY = Pattern.compile(
            "فيل|فلل|فل[ةه]|شق[ةه]|شقق|أرض|ارض|أراضي|اراضي|دور|دوبلكس|عمار|محل|عقار|villa|apartment|land|shop",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern HANDOFF_PROMISE = Pattern.compile(
            "أحو[ّ]?ل|احو[ّ]?ل|بحو[ّ]?ل|سأحو[ّ]?ل|سأقوم بتحويل|تحويلك|يتواصل معك|سيتواصل|بيتواصل|بيكلمك|يكلمك|transfer|connect you",
            Pattern.CASE_INSENSITIVE);

    /** The model sometimes writes a tool call as text instead of calling it; that must never reach a customer. */
    private static final Pattern LEAKED_TOOL_SYNTAX = Pattern.compile(
            "</?tools?>|</?call|default_api|functionCall|tool_code|search_listings|save_lead|get_viewing_slots|book_viewing|cancel_viewing|send_listing_card|handoff_to_marketer",
            Pattern.CASE_INSENSITIVE);
    /** "I'll look for you" without actually searching. */
    private static final Pattern SEARCH_PROMISE = Pattern.compile(
            "بدور لك|أدور لك|ادور لك|أبحث لك|ابحث لك|بأبحث|سأبحث|بشوف لك|أشوف لك|let me (check|look|search)",
            Pattern.CASE_INSENSITIVE);

    static final String NO_TOOL_TEXT = "تنبيه داخلي لا يراه العميل: كتبت استدعاء أداة كنص داخل الرد. "
            + "لا تكتب أسماء الأدوات أو صيغتها أبداً؛ استدعِ الأداة استدعاءً فعلياً ثم اكتب للعميل رداً عادياً فقط.";
    static final String MUST_SEARCH = "تنبيه داخلي لا يراه العميل: لم تستدعِ search_listings في هذا الرد. "
            + "لا تؤكد ولا تنفِ توفر أي عقار قبل البحث. استدعِ search_listings الآن بالمعلومات المتوفرة ثم أعد كتابة ردك.";
    static final String MUST_HANDOFF = "تنبيه داخلي لا يراه العميل: قلت إنك ستحوّل العميل أو إن أحداً سيتواصل معه، "
            + "لكنك لم تستدعِ handoff_to_marketer. استدعها الآن ثم أعد كتابة ردك.";

    /**
     * Returns an internal note when the reply relies on a tool the model did not call:
     * claiming a property is (or is not) available without searching, or promising a human
     * without handing off. Questions ("أحولك للمسوّق؟") are offers, not promises.
     */
    /** Sent to the model (never the customer) when it returned no text. */
    public static final String EMPTY_REPLY =
            "(تنبيه داخلي من النظام، لا تذكره للعميل) لم تكتب رداً للعميل. اكتب الآن ردك للعميل على آخر رسالة بناءً على نتائج الأدوات.";

    public static String correction(String reply, Set<String> toolsCalled, boolean handedOff) {
        if (reply == null) {
            return null;
        }
        if (LEAKED_TOOL_SYNTAX.matcher(reply).find()) {
            return NO_TOOL_TEXT;
        }
        boolean searched = toolsCalled.contains(AgentTools.SEARCH);
        if (!searched && AVAILABILITY.matcher(reply).find() && PROPERTY.matcher(reply).find()) {
            return MUST_SEARCH;
        }
        if (!searched && SEARCH_PROMISE.matcher(reply).find()) {
            return MUST_SEARCH;
        }
        if (!handedOff) {
            for (String sentence : reply.split("(?<=[.!؟?\\n])")) {
                String s = sentence.trim();
                if (!s.endsWith("؟") && !s.endsWith("?") && HANDOFF_PROMISE.matcher(s).find()) {
                    return MUST_HANDOFF;
                }
            }
        }
        return null;
    }

    public static Set<String> licensesIn(String text) {
        Set<String> out = new HashSet<>();
        if (text == null) {
            return out;
        }
        Matcher m = LICENSE.matcher(normalizeDigits(text));
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }

    static String normalizeDigits(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            if (c >= '٠' && c <= '٩') {
                b.append((char) ('0' + (c - '٠')));
            } else if (c >= '۰' && c <= '۹') {
                b.append((char) ('0' + (c - '۰')));
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }
}
