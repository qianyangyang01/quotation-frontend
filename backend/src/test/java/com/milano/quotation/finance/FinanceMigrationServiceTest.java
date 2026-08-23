package com.milano.quotation.finance;

import com.milano.quotation.common.AppException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class FinanceMigrationServiceTest {
    private FinanceSettingRepository repository;private FinanceMigrationService service;
    @BeforeEach void setup(){repository=mock(FinanceSettingRepository.class);when(repository.saveAndFlush(any())).thenAnswer(call->call.getArgument(0));service=new FinanceMigrationService(repository);}
    @Test void fillsBootstrapEmptySettingAndRecordsBeforeValue(){var existing=FinanceSetting.create("channel-policies",JsonNodeFactory.instance.arrayNode());when(repository.findById("channel-policies")).thenReturn(Optional.of(existing));var value=JsonNodeFactory.instance.arrayNode();value.addObject().put("id","普货");var result=service.apply("channel-policies",value,false);assertTrue(result.path("changed").asBoolean());assertEquals("普货",existing.payload.get(0).path("id").asText());assertTrue(result.path("before").isEmpty());}
    @Test void treatsIdenticalValueAsIdempotent(){var value=JsonNodeFactory.instance.objectNode().put("usdCny",7.2);var existing=FinanceSetting.create("exchange-rate",value);when(repository.findById("exchange-rate")).thenReturn(Optional.of(existing));assertFalse(service.apply("exchange-rate",value,false).path("changed").asBoolean());}
    @Test void blocksDifferentBusinessValueWithoutReplaceAndAllowsApprovedReplace(){var existing=FinanceSetting.create("exchange-rate",JsonNodeFactory.instance.objectNode().put("usdCny",7.1));when(repository.findById("exchange-rate")).thenReturn(Optional.of(existing));var next=JsonNodeFactory.instance.objectNode().put("usdCny",7.2);assertThrows(AppException.class,()->service.apply("exchange-rate",next,false));assertTrue(service.apply("exchange-rate",next,true).path("changed").asBoolean());}
    @Test void rollsBackCreatedAndUpdatedSettings(){var changes=JsonNodeFactory.instance.arrayNode();changes.addObject().put("key","channel-policies").put("changed",true).putNull("before");changes.addObject().put("key","exchange-rate").put("changed",true).set("before",JsonNodeFactory.instance.objectNode().put("usdCny",7.1));var exchange=FinanceSetting.create("exchange-rate",JsonNodeFactory.instance.objectNode().put("usdCny",7.2));when(repository.findById("exchange-rate")).thenReturn(Optional.of(exchange));service.rollback(changes);verify(repository).deleteById("channel-policies");assertEquals(7.1,exchange.payload.path("usdCny").asDouble());}
}
