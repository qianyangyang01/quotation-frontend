package com.milano.quotation.idempotency;

import com.milano.quotation.common.AppException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class IdempotencyServiceTest {
    private IdempotencyRepository repository;private IdempotencyService service;
    @BeforeEach void setup(){repository=mock(IdempotencyRepository.class);service=new IdempotencyService(repository);}
    @Test void validatesKeyAndReturnsDefensiveCachedResponse(){var request=JsonNodeFactory.instance.objectNode().put("sku","SKU-1");assertThrows(AppException.class,()->service.existing("ADMIN","create",null,request));assertThrows(AppException.class,()->service.existing("ADMIN","create","short",request));var row=new IdempotencyRecord();row.requestHash=hash(request);row.responseBody=JsonNodeFactory.instance.objectNode().put("ok",true);when(repository.findByAccountAndOperationAndIdempotencyKey("ADMIN","create","valid-key-001")).thenReturn(Optional.of(row));var result=service.existing("ADMIN","create","valid-key-001",request).orElseThrow();assertTrue(result.path("ok").asBoolean());assertNotSame(row.responseBody,result);assertThrows(AppException.class,()->service.existing("ADMIN","create","valid-key-001",JsonNodeFactory.instance.objectNode().put("sku","DIFFERENT")));}
    @Test void savesOnceAndIgnoresExistingKey(){var request=JsonNodeFactory.instance.objectNode().put("sku","SKU-1");var response=JsonNodeFactory.instance.objectNode().put("id","1");when(repository.findByAccountAndOperationAndIdempotencyKey("ADMIN","create","valid-key-002")).thenReturn(Optional.empty());service.save("ADMIN","create","valid-key-002",request,response);verify(repository).save(argThat(row->row.requestHash.equals(hash(request))&&row.responseBody.equals(response)));reset(repository);var existing=new IdempotencyRecord();when(repository.findByAccountAndOperationAndIdempotencyKey(anyString(),anyString(),anyString())).thenReturn(Optional.of(existing));service.save("ADMIN","create","valid-key-002",request,response);verify(repository,never()).save(any());}
    private String hash(tools.jackson.databind.JsonNode request){try{var digest=java.security.MessageDigest.getInstance("SHA-256").digest(request.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));return java.util.HexFormat.of().formatHex(digest);}catch(Exception e){throw new AssertionError(e);}}
}
