package com.milano.quotation.logistics;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LogisticsParserCompatibilityTest {
    @Test void onlyExplicitlyReviewedProducerVersionsAreAccepted(){
        assertEquals("",LogisticsParserCompatibility.blockingReason(LogisticsSourceParser.VERSION));
        assertEquals("",LogisticsParserCompatibility.blockingReason("wanbang-yanwen-express-2026.09.29-v3"));
        for(var version:new String[]{"","unknown","wanbang-yanwen-express-2026.09.29-v2","wanbang-yanwen-express-2026.09.29-v3-untrusted","future-version"})
            assertFalse(LogisticsParserCompatibility.blockingReason(version).isBlank(),version);
        assertFalse(LogisticsParserCompatibility.blockingReason(null).isBlank());
    }
}
