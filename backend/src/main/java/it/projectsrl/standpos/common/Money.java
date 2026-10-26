package it.projectsrl.standpos.common;

/** Formats integer euro cents the Italian way ({@code 1.234,50}) for printed documents. No floating point. */
public final class Money {

    private Money() {}

    public static String format(long cents) {
        String sign = cents < 0 ? "-" : "";
        long abs = Math.abs(cents);
        String euros = Long.toString(abs / 100);
        StringBuilder grouped = new StringBuilder();
        for (int i = 0; i < euros.length(); i++) {
            if (i > 0 && (euros.length() - i) % 3 == 0) {
                grouped.append('.');
            }
            grouped.append(euros.charAt(i));
        }
        long rest = abs % 100;
        return sign + grouped + "," + (rest < 10 ? "0" : "") + rest;
    }

    /** With the euro sign, as on the total line: {@code € 9,00}. */
    public static String euro(long cents) {
        return "€ " + format(cents);
    }
}
