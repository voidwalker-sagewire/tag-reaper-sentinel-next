package com.herdmate.tagreapersentinel;

import org.junit.Test;
import static org.junit.Assert.*;

public class SellEtonFrameParserTest {
    @Test public void parsesObservedStableGrossFrame() {
        SellEtonFrameParser.Result result = SellEtonFrameParser.parse("ST,GS,+ 125lb");
        assertNotNull(result);
        assertTrue(result.stable);
        assertEquals("GS", result.mode);
        assertEquals(125d, result.pounds, 0.001d);
    }

    @Test public void convertsKilogramsAndRejectsNoise() {
        assertEquals(220.462d, SellEtonFrameParser.parse("ST NT +100kg").pounds, 0.001d);
        assertNull(SellEtonFrameParser.parse("hello"));
    }
}
