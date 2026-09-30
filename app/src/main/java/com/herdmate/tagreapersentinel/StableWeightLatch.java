package com.herdmate.tagreapersentinel;

import java.util.ArrayDeque;
import java.util.Deque;

/** Requires three stable readings within one configured scale division. */
public final class StableWeightLatch {
    private final int requiredFrames;
    private final double tolerancePounds;
    private final Deque<SellEtonFrameParser.Result> readings = new ArrayDeque<>();
    private SellEtonFrameParser.Result locked;

    public StableWeightLatch(int requiredFrames, double tolerancePounds) {
        this.requiredFrames = requiredFrames;
        this.tolerancePounds = tolerancePounds;
    }

    public SellEtonFrameParser.Result offer(SellEtonFrameParser.Result reading) {
        if (locked != null) return locked;
        if (reading == null || !reading.stable) {
            readings.clear();
            return null;
        }
        readings.addLast(reading);
        while (readings.size() > requiredFrames) readings.removeFirst();
        if (readings.size() < requiredFrames) return null;
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (SellEtonFrameParser.Result value : readings) {
            min = Math.min(min, value.pounds);
            max = Math.max(max, value.pounds);
        }
        if (max - min > tolerancePounds) {
            while (readings.size() > 1) readings.removeFirst();
            return null;
        }
        locked = reading;
        return locked;
    }

    public SellEtonFrameParser.Result getLocked() { return locked; }

    public void reset() {
        readings.clear();
        locked = null;
    }
}
