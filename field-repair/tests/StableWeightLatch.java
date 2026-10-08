package com.herdmate.tagreapersentinel;

import com.herdmate.tagreapersentinel.SellEtonFrameParser;
import java.util.ArrayDeque;
import java.util.Deque;

/* loaded from: classes3.dex */
public final class StableWeightLatch {
    private SellEtonFrameParser.Result locked;
    private final Deque<SellEtonFrameParser.Result> readings = new ArrayDeque();
    private final int requiredFrames;
    private final double tolerancePounds;

    public StableWeightLatch(int requiredFrames, double tolerancePounds) {
        this.requiredFrames = requiredFrames;
        this.tolerancePounds = tolerancePounds;
    }

    public SellEtonFrameParser.Result offer(SellEtonFrameParser.Result reading) {
        SellEtonFrameParser.Result result = this.locked;
        if (result != null) {
            return result;
        }
        if (reading != null && reading.stable) {
            double d = reading.pounds;
            if (d > 0.0d && !Double.isNaN(d) && !Double.isInfinite(d)) {
                this.readings.addLast(reading);
                while (this.readings.size() > this.requiredFrames) {
                    this.readings.removeFirst();
                }
                if (this.readings.size() < this.requiredFrames) {
                    return null;
                }
                double min = Double.MAX_VALUE;
                double max = -1.7976931348623157E308d;
                for (SellEtonFrameParser.Result value : this.readings) {
                    min = Math.min(min, value.pounds);
                    max = Math.max(max, value.pounds);
                }
                if (max - min > this.tolerancePounds) {
                    while (this.readings.size() > 1) {
                        this.readings.removeFirst();
                    }
                    return null;
                }
                this.locked = reading;
                return reading;
            }
        }
        this.readings.clear();
        return null;
    }

    public SellEtonFrameParser.Result getLocked() {
        return this.locked;
    }

    public void reset() {
        this.readings.clear();
        this.locked = null;
    }
}
