package com.milano.quotation.logistics;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LogisticsParserCompatibilityTest {
    @Test void onlyExplicitlyReviewedProducerVersionsAreAccepted(){
        assertEquals("",LogisticsParserCompatibility.blockingReason(LogisticsSourceParser.VERSION));
        assertEquals("",LogisticsParserCompatibility.blockingReason("wanbang-specialb-sensitive-name-2026.11.21-v26"));
        assertEquals("",LogisticsParserCompatibility.blockingReason("yanwen-japan-first-next-2026.11.21-v27"));
        assertEquals("",LogisticsParserCompatibility.blockingReason("yunexpress-japan-first-next-2026.11.23-v28"));
        assertEquals("",LogisticsParserCompatibility.blockingReason("mutable-product-identities-2026.11.19-v24"));
        assertEquals("",LogisticsParserCompatibility.blockingReason("source-date-reference-2026.10.20-v11"));
        assertEquals("",LogisticsParserCompatibility.blockingReason("source-effective-date-2026.10.19-v9"));
        assertEquals("",LogisticsParserCompatibility.blockingReason("wanbang-yanwen-express-2026.09.29-v3"));
        for(var version:new String[]{"","unknown","wanbang-yanwen-express-2026.09.29-v2","wanbang-yanwen-express-2026.09.29-v3-untrusted","future-version"})
            assertFalse(LogisticsParserCompatibility.blockingReason(version).isBlank(),version);
        assertFalse(LogisticsParserCompatibility.blockingReason(null).isBlank());
    }
}
