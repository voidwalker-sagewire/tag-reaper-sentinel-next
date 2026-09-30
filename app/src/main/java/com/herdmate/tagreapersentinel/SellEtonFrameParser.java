package com.herdmate.tagreapersentinel;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses the ASCII weight frames emitted by the installed SellEton indicator. */
public final class SellEtonFrameParser {
    private static final Pattern FRAME = Pattern.compile(
            "^\\s*(ST|US)\\s*[, ]+\\s*(GS|NT)\\s*[, ]+\\s*([+-])?\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(lb|lbs|kg)\\s*$",
            Pattern.CASE_INSENSITIVE);

    private SellEtonFrameParser() {}

    public static Result parse(String raw) {
        if (raw == null) return null;
        Matcher matcher = FRAME.matcher(raw);
        if (!matcher.matches()) return null;
        String stability = matcher.group(1).toUpperCase(Locale.US);
        String mode = matcher.group(2).toUpperCase(Locale.US);
        String sign = matcher.group(3) == null ? "+" : matcher.group(3);
        double original = Double.parseDouble(matcher.group(4));
        if ("-".equals(sign)) original = -original;
        String unit = matcher.group(5).toLowerCase(Locale.US);
        if ("lbs".equals(unit)) unit = "lb";
        double pounds = "kg".equals(unit) ? original * 2.2046226218d : original;
        return new Result(raw.trim(), "ST".equals(stability), mode, sign, original, unit, pounds);
    }

    public static final class Result {
        public final String raw;
        public final boolean stable;
        public final String mode;
        public final String sign;
        public final double originalValue;
        public final String originalUnit;
        public final double pounds;

        Result(String raw, boolean stable, String mode, String sign,
               double originalValue, String originalUnit, double pounds) {
            this.raw = raw;
            this.stable = stable;
            this.mode = mode;
            this.sign = sign;
            this.originalValue = originalValue;
            this.originalUnit = originalUnit;
            this.pounds = pounds;
        }
    }
}
