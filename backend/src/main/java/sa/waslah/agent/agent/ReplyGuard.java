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
