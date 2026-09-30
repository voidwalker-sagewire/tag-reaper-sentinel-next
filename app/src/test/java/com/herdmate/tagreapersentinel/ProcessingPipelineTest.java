package com.herdmate.tagreapersentinel;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public class ProcessingPipelineTest {
    @Test public void twoAnimalsNeverExchangeRecords() {
        ProcessingPipeline pipeline = new ProcessingPipeline();
        pipeline.scan("EPC-A", "1", -40, 1L, Arrays.asList("Wormer"));
        assertTrue(pipeline.attachWeight(SellEtonFrameParser.parse("ST,GS,+ 125lb"), 2L));
        assertTrue(pipeline.advance(3L));
        pipeline.inHeadChute.checklist.get("Wormer").status = "COMPLETED";

        pipeline.scan("EPC-B", "1", -39, 4L, Arrays.asList("Wormer"));
        assertTrue(pipeline.attachWeight(SellEtonFrameParser.parse("ST,GS,+ 140lb"), 5L));

        assertEquals("EPC-A", pipeline.inHeadChute.epc);
        assertEquals(125d, pipeline.inHeadChute.weightPounds, 0.001d);
        assertEquals("COMPLETED", pipeline.inHeadChute.checklist.get("Wormer").status);
        assertEquals("EPC-B", pipeline.onScale.epc);
        assertEquals(140d, pipeline.onScale.weightPounds, 0.001d);
        assertEquals("PENDING", pipeline.onScale.checklist.get("Wormer").status);
        assertFalse(pipeline.advance(6L));
    }

    @Test public void differentSecondTagCreatesAmbiguityAndBlocksWeight() {
        ProcessingPipeline pipeline = new ProcessingPipeline();
        pipeline.scan("EPC-A", "1", -40, 1L, Arrays.asList("Observe"));
        assertEquals(ProcessingPipeline.ScanResult.AMBIGUOUS,
                pipeline.scan("EPC-B", "1", -38, 2L, Arrays.asList("Observe")));
        assertFalse(pipeline.attachWeight(SellEtonFrameParser.parse("ST,GS,+ 125lb"), 3L));
        assertFalse(pipeline.canAdvance());
    }
}
