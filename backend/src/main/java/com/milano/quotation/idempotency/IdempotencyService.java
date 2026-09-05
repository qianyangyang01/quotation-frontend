package com.milano.quotation.idempotency;

import tools.jackson.databind.JsonNode;
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
    private final IdempotencyRepository records;
    private final org.springframework.jdbc.core.simple.JdbcClient jdbc;
    private final boolean postgres;
    private final org.springframework.transaction.support.TransactionTemplate transactions;
    public IdempotencyService(IdempotencyRepository records){this.records=records;this.jdbc=null;this.postgres=false;this.transactions=null;}
    @org.springframework.beans.factory.annotation.Autowired
    public IdempotencyService(IdempotencyRepository records, org.springframework.jdbc.core.simple.JdbcClient jdbc, javax.sql.DataSource source, org.springframework.transaction.PlatformTransactionManager manager) throws java.sql.SQLException {
        this.records=records;this.jdbc=jdbc;
        this.transactions=new org.springframework.transaction.support.TransactionTemplate(manager);
        try(var connection=source.getConnection()){this.postgres=connection.getMetaData().getDatabaseProductName().equals("PostgreSQL");}
    }
    @Transactional public Optional<JsonNode> existing(String account,String operation,String key,JsonNode request){
        validateKey(key);
        // Hold the request lock through the caller's business transaction and response write.
        lock(account,operation,key);
        var hash=hash(request);return records.findByAccountAndOperationAndIdempotencyKey(account,operation,key).map(row->{if(!row.requestHash.equals(hash))throw AppException.conflict("同一幂等键不能用于不同请求");if(row.responseStatus==102)throw AppException.conflict("该请求正在处理，请查看进度，完成后刷新重试");return row.responseBody.deepCopy();});
    }
    private void lock(String account,String operation,String key){
        if(jdbc!=null){
            if(postgres)jdbc.sql("select pg_advisory_xact_lock(hashtext(:actor),hashtext(:request))").param("actor",account).param("request",operation+":"+key).query(rs->true);
            else jdbc.sql("select id from app_user where account=:actor for update").param("actor",account).query(UUID.class).list();
        }
    }
    /** Keep the durable claim separate from per-channel transactions; never hold a connection while publishing. */
    public JsonNode executeIndependent(String account,String operation,String key,JsonNode request,java.util.function.Supplier<JsonNode> work){
        validateKey(key);var requestHash=hash(request);var claimId=UUID.randomUUID();
        var cached=transactions.execute(status->{
            lock(account,operation,key);var prior=records.findByAccountAndOperationAndIdempotencyKey(account,operation,key);
            if(prior.isPresent()){
                var row=prior.get();if(!row.requestHash.equals(requestHash))throw AppException.conflict("同一幂等键不能用于不同请求");
                if(row.responseStatus!=102)return row.responseBody.deepCopy();
                if(row.createdAt.isAfter(Instant.now().minusSeconds(900)))throw AppException.conflict("该批次正在发布，请查看进度，完成后刷新重试");
                records.delete(row);records.flush();
            }
            var claim=new IdempotencyRecord();claim.id=claimId;claim.account=account;claim.operation=operation;claim.idempotencyKey=key;claim.requestHash=requestHash;claim.responseStatus=102;claim.responseBody=tools.jackson.databind.node.JsonNodeFactory.instance.objectNode();claim.createdAt=Instant.now();records.saveAndFlush(claim);return null;
        });
        if(cached!=null)return cached;
        try{
            var result=work.get();
            transactions.executeWithoutResult(status->{var claim=records.findById(claimId).orElseThrow(()->AppException.conflict("发布请求已恢复，请刷新查看最新进度"));claim.responseStatus=200;claim.responseBody=result.deepCopy();records.saveAndFlush(claim);});
            return result;
        }catch(RuntimeException failure){transactions.executeWithoutResult(status->records.findById(claimId).filter(row->row.responseStatus==102).ifPresent(records::delete));throw failure;}
    }
    @Transactional public void save(String account,String operation,String key,JsonNode request,JsonNode response){
        validateKey(key);if(records.findByAccountAndOperationAndIdempotencyKey(account,operation,key).isPresent())return;var row=new IdempotencyRecord();row.id=UUID.randomUUID();row.account=account;row.operation=operation;row.idempotencyKey=key;row.requestHash=hash(request);row.responseStatus=200;row.responseBody=response.deepCopy();row.createdAt=Instant.now();records.save(row);
    }
    private static void validateKey(String key){if(key==null||!key.matches("[A-Za-z0-9._:-]{8,120}"))throw AppException.unprocessable("缺少或无效的 Idempotency-Key");}
    private static String hash(JsonNode request){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(request.toString().getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
