package com.herdmate.tagreapersentinel;

import android.os.Handler;

public class RepairChecks {
    static int checks;
    static void check(boolean condition, String reason) {
        checks++;
        if (!condition) throw new AssertionError(reason);
    }
    static SellEtonFrameParser.Result reading(String frame) { return SellEtonFrameParser.parse(frame); }
    static void rejects(String frame) {
        StableWeightLatch latch = new StableWeightLatch(3, 2.0);
        for (int i = 0; i < 10; i++) check(latch.offer(reading(frame)) == null, "Must reject " + frame);
    }
    public static void main(String[] args) {
        // Actual field export includes both of these invalid stable weight frames.
        rejects("ST,GS,-      2lb");
        rejects("ST,GS,+      0lb");
        rejects("US,GS,+547lb");
        rejects("malformed serial data");
        StableWeightLatch latch = new StableWeightLatch(3, 2.0);
        check(latch.offer(reading("ST,GS,+547lb")) == null, "First frame cannot lock");
        check(latch.offer(reading("ST,GS,+548lb")) == null, "Second frame cannot lock");
        check(latch.offer(reading("ST,GS,+547lb")).pounds == 547.0, "Third agreeing positive frame locks");
        check(latch.offer(reading("ST,GS,+600lb")).pounds == 547.0, "Locked reading immutable until reset");
        latch.reset();
        check(latch.getLocked() == null, "Reset clears previous animal weight");
        latch.offer(reading("ST,GS,+500lb"));
        latch.offer(reading("ST,GS,+501lb"));
        check(latch.offer(reading("ST,GS,+0lb")) == null, "Invalid sample clears partial agreement");
        check(latch.offer(reading("ST,GS,+501lb")) == null, "Fresh agreement required after invalid frame");
        check(latch.offer(reading("ST,GS,+502lb")) == null, "Two fresh frames insufficient");
        check(latch.offer(reading("ST,GS,+501lb")) != null, "Three fresh frames lock");
        check(Math.abs(reading("ST,NT,+100kg").pounds - 220.46226218) < 0.00001, "Kilogram conversion preserved");
        SellEtonFrameParser.Result infinity = new SellEtonFrameParser.Result("", true, "GS", "+", Double.POSITIVE_INFINITY, "lb", Double.POSITIVE_INFINITY);
        SellEtonFrameParser.Result nan = new SellEtonFrameParser.Result("", true, "GS", "+", Double.NaN, "lb", Double.NaN);
        latch.reset();
        for (int i = 0; i < 4; i++) check(latch.offer(infinity) == null && latch.offer(nan) == null, "Nonfinite weights rejected");

        Handler handler = new Handler();
        int[] rendered = {0};
        LiveRefreshGate gate = new LiveRefreshGate(handler, () -> rendered[0]++);
        for (int tick = 0; tick < 240; tick++) {
            for (int read = 0; read < 1000; read++) gate.request();
            check(handler.queue.size() == 1, "Only one live refresh queued during burst");
            check(handler.delay == 250, "Four live refreshes per second maximum");
            handler.tick();
        }
        check(rendered[0] == 240 && handler.posts == 240, "240000 requests coalesced to 240 renders");
        handler.accept = false;
        gate.request();
        check(handler.queue.isEmpty(), "Rejected handler post not queued");
        handler.accept = true;
        gate.request();
        check(handler.queue.size() == 1, "Post failure cannot permanently suppress refresh");
        handler.tick();
        check(rendered[0] == 241, "Refresh resumes after failed post");
        System.out.println("PASS: " + checks + " checks; actual APK weight latch and production refresh gate.");
    }
}
