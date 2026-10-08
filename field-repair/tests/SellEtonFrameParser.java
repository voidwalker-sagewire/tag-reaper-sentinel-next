package com.herdmate.tagreapersentinel;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/* loaded from: classes3.dex */
public final class SellEtonFrameParser {
    private static final Pattern FRAME = Pattern.compile("^\\s*(ST|US)\\s*[, ]+\\s*(GS|NT)\\s*[, ]+\\s*([+-])?\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(lb|lbs|kg)\\s*$", 2);

    private SellEtonFrameParser() {
    }

    public static Result parse(String raw) throws NumberFormatException {
        if (raw == null) {
            return null;
        }
        Matcher matcher = FRAME.matcher(raw);
        if (!matcher.matches()) {
            return null;
        }
        String stability = matcher.group(1).toUpperCase(Locale.US);
        String mode = matcher.group(2).toUpperCase(Locale.US);
        String sign = matcher.group(3) == null ? "+" : matcher.group(3);
        double original = Double.parseDouble(matcher.group(4));
        if ("-".equals(sign)) {
            original = -original;
        }
        double original2 = original;
        String unit = matcher.group(5).toLowerCase(Locale.US);
        if ("lbs".equals(unit)) {
            unit = "lb";
        }
        String unit2 = unit;
        double pounds = "kg".equals(unit2) ? 2.2046226218d * original2 : original2;
        return new Result(raw.trim(), "ST".equals(stability), mode, sign, original2, unit2, pounds);
    }

    public static final class Result {
        public final String mode;
        public final String originalUnit;
        public final double originalValue;
        public final double pounds;
        public final String raw;
        public final String sign;
        public final boolean stable;

        Result(String raw, boolean stable, String mode, String sign, double originalValue, String originalUnit, double pounds) {
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
