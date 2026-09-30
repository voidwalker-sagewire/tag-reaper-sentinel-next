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

    @Test public void untaggedAnimalKeepsWeightThroughDeskCommissioning() {
        ProcessingPipeline pipeline = new ProcessingPipeline();
        assertTrue(pipeline.createProvisional("UNTAGGED-001", "VIS-27", 1L,
                Arrays.asList("Planned treatment")));
        assertTrue(pipeline.attachWeight(SellEtonFrameParser.parse("ST,GS,+ 412lb"), 2L));
        assertTrue(pipeline.advance(3L));
        assertEquals("UNTAGGED-001", pipeline.inHeadChute.provisionalIdentity);
        assertEquals(412d, pipeline.inHeadChute.weightPounds, 0.001d);

        assertEquals(ProcessingPipeline.TagResult.RESERVED,
                pipeline.reserveDeskTag("NEW-EPC-27", "ANT2", -18d, 4L, "Michael"));
        assertEquals("RESERVED", pipeline.inHeadChute.uhfIdentityState);
        assertTrue(pipeline.markTagInstalled(5L, "Michael"));
        assertEquals("NEW-EPC-27", pipeline.inHeadChute.epc);
        assertEquals("INSTALLED", pipeline.inHeadChute.uhfIdentityState);
        assertEquals(412d, pipeline.inHeadChute.weightPounds, 0.001d);
    }

    @Test public void deskTagCannotStealNextScaleAnimalIdentity() {
        ProcessingPipeline pipeline = new ProcessingPipeline();
        pipeline.createProvisional("UNTAGGED-001", "", 1L, Arrays.asList("Observe"));
        pipeline.attachWeight(SellEtonFrameParser.parse("ST,GS,+ 300lb"), 2L);
        pipeline.advance(3L);
        pipeline.scan("ANIMAL-B", "ANT1", -35d, 4L, Arrays.asList("Observe"));
        pipeline.attachWeight(SellEtonFrameParser.parse("ST,GS,+ 325lb"), 5L);

        assertEquals(ProcessingPipeline.TagResult.RESERVED,
                pipeline.reserveDeskTag("NEW-EPC-A", "ANT2", -12d, 6L, "Michael"));
        assertTrue(pipeline.markTagInstalled(7L, "Michael"));
        assertEquals("NEW-EPC-A", pipeline.inHeadChute.epc);
        assertEquals("ANIMAL-B", pipeline.onScale.epc);
        assertEquals(300d, pipeline.inHeadChute.weightPounds, 0.001d);
        assertEquals(325d, pipeline.onScale.weightPounds, 0.001d);
    }

    @Test public void duplicateDeskTagIsRejected() {
        ProcessingPipeline pipeline = new ProcessingPipeline();
        pipeline.scan("EXISTING", "ANT1", -30d, 1L, Arrays.asList("Observe"));
        pipeline.attachWeight(SellEtonFrameParser.parse("ST,GS,+ 300lb"), 2L);
        pipeline.advance(3L);
        pipeline.finish(false, "Pasture", "", 4L, "Michael");
        pipeline.createProvisional("UNTAGGED-002", "", 5L, Arrays.asList("Observe"));
        pipeline.attachWeight(SellEtonFrameParser.parse("ST,GS,+ 320lb"), 6L);
        pipeline.advance(7L);
        assertEquals(ProcessingPipeline.TagResult.DUPLICATE,
                pipeline.reserveDeskTag("EXISTING", "ANT2", -15d, 8L, "Michael"));
    }
}
