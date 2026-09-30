package com.herdmate.tagreapersentinel;

import org.junit.Test;
import static org.junit.Assert.*;

public class StableWeightLatchTest {
    @Test public void locksOnlyAfterThreeAgreeingStableFrames() {
        StableWeightLatch latch = new StableWeightLatch(3, 2d);
        assertNull(latch.offer(SellEtonFrameParser.parse("ST,GS,+ 124lb")));
        assertNull(latch.offer(SellEtonFrameParser.parse("ST,GS,+ 125lb")));
        assertNotNull(latch.offer(SellEtonFrameParser.parse("ST,GS,+ 125lb")));
    }

    @Test public void unstableFrameResetsCandidate() {
        StableWeightLatch latch = new StableWeightLatch(3, 2d);
        latch.offer(SellEtonFrameParser.parse("ST,GS,+ 125lb"));
        latch.offer(SellEtonFrameParser.parse("US,GS,+ 125lb"));
        assertNull(latch.offer(SellEtonFrameParser.parse("ST,GS,+ 125lb")));
    }
}
