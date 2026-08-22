package com.milano.quotation.idempotency;

import com.fasterxml.jackson.databind.JsonNode;
import com.milano.quotation.common.AppException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

@Service
public class IdempotencyService {
    private final IdempotencyRepository records; public IdempotencyService(IdempotencyRepository records){this.records=records;}
    @Transactional(readOnly=true) public Optional<JsonNode> existing(String account,String operation,String key,JsonNode request){
        validateKey(key);var hash=hash(request);return records.findByAccountAndOperationAndIdempotencyKey(account,operation,key).map(row->{if(!row.requestHash.equals(hash))throw AppException.conflict("同一幂等键不能用于不同请求");return row.responseBody.deepCopy();});
    }
    @Transactional public void save(String account,String operation,String key,JsonNode request,JsonNode response){
        validateKey(key);if(records.findByAccountAndOperationAndIdempotencyKey(account,operation,key).isPresent())return;var row=new IdempotencyRecord();row.id=UUID.randomUUID();row.account=account;row.operation=operation;row.idempotencyKey=key;row.requestHash=hash(request);row.responseStatus=200;row.responseBody=response.deepCopy();row.createdAt=Instant.now();records.save(row);
    }
    private static void validateKey(String key){if(key==null||!key.matches("[A-Za-z0-9._:-]{8,120}"))throw AppException.unprocessable("缺少或无效的 Idempotency-Key");}
    private static String hash(JsonNode request){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(request.toString().getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
