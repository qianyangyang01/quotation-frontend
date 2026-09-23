package com.milano.quotation.logistics;

import com.milano.quotation.common.AppException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class LogisticsImportCoverageTest {
    final ObjectMapper mapper = new ObjectMapper();
    ObjectNode channel(String id,String provider,String name,String version) {
        return mapper.createObjectNode().put("channelId",id).put("providerName",provider).put("channelName",name)
            .put("versionId",version).put("versionNumber",4).put("sourceFile","9月10日报价.xlsx");
    }
    ObjectNode additions() {
        var payload=mapper.createObjectNode();payload.putArray("results").addObject().put("channelId","new").put("providerName","燕文").put("status","draft");return payload;
    }
    @Test void newChannelsDoNotHideExistingCosmeticsPrices() {
        var report=LogisticsImportCoverage.calculate(additions(),List.of(channel("old","燕文","燕文化妆品专线","v4"),channel("other","云途","其他","v2")));
        assertEquals(1,report.path("existingChannels").asInt());assertEquals(1,report.path("missingCount").asInt());
        assertEquals("燕文化妆品专线",report.path("missingChannels").get(0).path("channelName").asText());
        assertThrows(AppException.class,()->LogisticsImportCoverage.requireAcknowledgment(report,mapper.createObjectNode()));
        assertThrows(AppException.class,()->LogisticsImportCoverage.requireAcknowledgment(report,mapper.createObjectNode().put("partialUpdateConfirmed",true).put("coverageToken","stale")));
        assertDoesNotThrow(()->LogisticsImportCoverage.requireAcknowledgment(report,mapper.createObjectNode().put("partialUpdateConfirmed",true).put("coverageToken",report.path("token").asText())));
    }
    @Test void unchangedRowsCountAsIncludedByIdentityNotNameAndStableOrdering() {
        var payload=additions();payload.withArray("results").addObject().put("channelId","old").put("providerName","燕文").put("status","unchanged");
        var old=channel("old","燕文","已改名的化妆品渠道","v4");var other=channel("another","燕文","另一条线","v1");
        var report=LogisticsImportCoverage.calculate(payload,List.of(old,other));
        assertEquals(1,report.path("coveredChannels").asInt());assertEquals(1,report.path("missingCount").asInt());
        assertEquals(report.path("token"),LogisticsImportCoverage.calculate(payload,List.of(other,old)).path("token"));
        payload.withArray("results").addObject().put("channelId","another").put("providerName","燕文");
        var complete=LogisticsImportCoverage.calculate(payload,List.of(old,other));assertFalse(complete.path("partial").asBoolean());
        assertDoesNotThrow(()->LogisticsImportCoverage.requireAcknowledgment(complete,mapper.createObjectNode()));
    }
    @Test void concurrentPriceChangesInvalidateAcknowledgment() {
        var old=channel("old","燕文","化妆品","v4");var first=LogisticsImportCoverage.calculate(additions(),List.of(old));
        old.put("versionId","v5");var next=LogisticsImportCoverage.calculate(additions(),List.of(old));
        assertNotEquals(first.path("token"),next.path("token"));
        assertThrows(AppException.class,()->LogisticsImportCoverage.requireAcknowledgment(next,mapper.createObjectNode().put("partialUpdateConfirmed",true).put("coverageToken",first.path("token").asText())));
    }
    @Test void selectedAndFailedProviderStillShowsUncoveredPrices() {
        var payload=mapper.createObjectNode().put("selectedProviderName"," 燕 文 ");
        assertEquals(1,LogisticsImportCoverage.calculate(payload,List.of(channel("old","燕文","化妆品","v4"))).path("missingCount").asInt());
        payload.remove("selectedProviderName");payload.putArray("fileReports").addObject().putArray("sheets").addObject().putArray("channelMatches").addObject().put("providerName","燕文");
        assertEquals(1,LogisticsImportCoverage.calculate(payload,List.of(channel("old","燕文","化妆品","v4"))).path("missingCount").asInt());
    }
}
