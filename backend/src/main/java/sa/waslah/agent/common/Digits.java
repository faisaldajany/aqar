package sa.waslah.agent.common;

/** Digits the way people type them on Arabic keyboards (٠-٩, and Persian ۰-۹) turned into ASCII 0-9. */
public final class Digits {

    private Digits() {
    }

    public static String latin(String s) {
        if (s == null) return null;
        StringBuilder b = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            if (c >= '٠' && c <= '٩') b.append((char) ('0' + (c - '٠')));
            else if (c >= '۰' && c <= '۹') b.append((char) ('0' + (c - '۰')));
            else b.append(c);
        }
        return b.toString();
    }
}
