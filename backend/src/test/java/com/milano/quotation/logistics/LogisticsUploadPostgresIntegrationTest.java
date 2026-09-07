package com.milano.quotation.logistics;

import com.milano.quotation.common.AppException;
import com.milano.quotation.storage.AssetStorageService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.web.MockMultipartFile;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import tools.jackson.databind.ObjectMapper;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Testcontainers
class LogisticsUploadPostgresIntegrationTest {
    @Container static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16.4-alpine");
    static JdbcClient jdbc;
    static DataSourceTransactionManager manager;
    final ObjectMapper mapper=new ObjectMapper();
    AssetStorageService storage;
    LogisticsImportService imports;
    LogisticsUploadService uploads;
    UUID dataset;
    Map<String,byte[]> objects;
    @BeforeAll static void migrate(){
        var source=new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        jdbc=JdbcClient.create(source);manager=new DataSourceTransactionManager(source);
    }
    @BeforeEach void setup()throws Exception{
        jdbc.sql("delete from logistics_upload_session").update();
        dataset=jdbc.sql("select id from logistics_dataset where status='active'").query(UUID.class).single();
        objects=new ConcurrentHashMap<>();storage=mock(AssetStorageService.class);
        doAnswer(call->{objects.put(call.getArgument(0),((InputStream)call.getArgument(1)).readAllBytes());return null;})
            .when(storage).putRaw(anyString(),any(),anyLong(),anyString());
        when(storage.putRawWithSha256(anyString(),any(),anyLong(),anyString())).thenAnswer(call->{
            byte[] bytes=((InputStream)call.getArgument(1)).readAllBytes();objects.put(call.getArgument(0),bytes);return AssetStorageService.sha256(bytes);
        });
        when(storage.openRaw(anyString())).thenAnswer(call->new ByteArrayInputStream(Objects.requireNonNull(objects.get(call.getArgument(0)))));
        when(storage.removeRaw(anyString())).thenAnswer(call->{objects.remove(call.getArgument(0));return true;});
        imports=new LogisticsImportService(jdbc,mapper,storage,mock(LogisticsSourceParser.class),mock(LogisticsService.class),
            new LogisticsDatasetGuard(jdbc),manager,command->{});
        uploads=newService();
    }
    LogisticsUploadService newService(){return new LogisticsUploadService(jdbc,mapper,storage,new LogisticsDatasetGuard(jdbc),imports,manager);}
    LogisticsUploadService.Manifest manifest(byte[] bytes){
        return new LogisticsUploadService.Manifest(List.of(new LogisticsUploadService.FileSpec("花海.xlsx",bytes.length,AssetStorageService.sha256(bytes))),true);
    }
    UUID start(byte[] bytes,String actor,String key){return UUID.fromString(uploads.start(dataset,actor,key,manifest(bytes)).path("id").asText());}
    void part(UUID id,String actor,int index,byte[] bytes){
        uploads.chunk(id,actor,0,index,new MockMultipartFile("chunk",bytes),AssetStorageService.sha256(bytes));
    }
    @Test void resumesAfterServiceRestartAndCompletesExactlyOnce()throws Exception{
        byte[] bytes=new byte[LogisticsUploadService.CHUNK_BYTES+17];new Random(42).nextBytes(bytes);
        var id=start(bytes,"UPLOAD-QA","restart-123");
        part(id,"UPLOAD-QA",0,Arrays.copyOf(bytes,LogisticsUploadService.CHUNK_BYTES));
        uploads=newService();
        var restored=uploads.start(dataset,"UPLOAD-QA","restart-123",manifest(bytes));
        assertEquals(id.toString(),restored.path("id").asText());assertEquals(1,restored.path("received").get(0).size());
        assertThrows(AppException.class,()->uploads.complete(id,"UPLOAD-QA"));
        // A lost HTTP response retries the same chunk without appending it twice.
        part(id,"UPLOAD-QA",0,Arrays.copyOf(bytes,LogisticsUploadService.CHUNK_BYTES));
        part(id,"UPLOAD-QA",1,Arrays.copyOfRange(bytes,LogisticsUploadService.CHUNK_BYTES,bytes.length));
        String batch=uploads.complete(id,"UPLOAD-QA").path("id").asText();
        assertEquals(batch,newService().complete(id,"UPLOAD-QA").path("id").asText());
        assertArrayEquals(bytes,objects.get("logistics/imports/"+batch+"/0"));
        assertEquals(1,jdbc.sql("select count(*) from logistics_import_batch where request_key=:key").param("key","resumable:"+id).query(Integer.class).single());
        uploads.cleanup();
        assertFalse(objects.keySet().stream().anyMatch(k->k.startsWith("logistics/upload-chunks/")));
        assertTrue(objects.containsKey("logistics/imports/"+batch+"/0"));
        assertEquals(batch,newService().complete(id,"UPLOAD-QA").path("id").asText());
    }
    @Test void blocksOtherActorsChangedFilesAndCorruptChunks(){
        byte[] bytes={1,2,3};var id=start(bytes,"OWNER","ownership-123");
        assertThrows(AppException.class,()->part(id,"OTHER",0,bytes));
        assertThrows(AppException.class,()->uploads.complete(id,"OTHER"));
        assertThrows(AppException.class,()->uploads.start(dataset,"OWNER","ownership-123",manifest(new byte[]{3,2,1})));
        assertThrows(AppException.class,()->uploads.chunk(id,"OWNER",0,0,new MockMultipartFile("chunk",bytes),"0".repeat(64)));
        assertThrows(AppException.class,()->part(id,"OWNER",1,bytes));
        assertThrows(AppException.class,()->part(id,"OWNER",0,new byte[]{1}));
        part(id,"OWNER",0,new byte[]{3,2,1});
        assertThrows(AppException.class,()->uploads.complete(id,"OWNER"));
        assertThrows(AppException.class,()->part(id,"OWNER",0,bytes));
    }
    @Test void expiresAndCleansOnlyOwnedTemporaryChunks(){
        byte[] bytes={1};var id=start(bytes,"EXPIRY","expiry-123");part(id,"EXPIRY",0,bytes);
        objects.put("objects/business-image",bytes);
        jdbc.sql("update logistics_upload_session set expires_at=now()-interval '1 second' where id=:id").param("id",id).update();
        assertThrows(AppException.class,()->uploads.complete(id,"EXPIRY"));
        uploads.cleanup();
        assertEquals(Set.of("objects/business-image"),objects.keySet());
        assertEquals(0,jdbc.sql("select count(*) from logistics_upload_session where id=:id").param("id",id).query(Integer.class).single());
    }
    @Test void concurrentCompletionCreatesOneBatch()throws Exception{
        byte[] bytes={1,2,3};var id=start(bytes,"CONCURRENT","concurrent-123");part(id,"CONCURRENT",0,bytes);
        try(var pool=Executors.newFixedThreadPool(2)){
            var first=pool.submit(()->newService().complete(id,"CONCURRENT").path("id").asText());
            var second=pool.submit(()->newService().complete(id,"CONCURRENT").path("id").asText());
            assertEquals(first.get(15,TimeUnit.SECONDS),second.get(15,TimeUnit.SECONDS));
        }
    }
    @Test void enforcesSizeAndPendingSessionQuota(){
        var bad=new LogisticsUploadService.Manifest(List.of(new LogisticsUploadService.FileSpec("file.xlsx",101L*1024*1024,"0".repeat(64))),true);
        assertThrows(AppException.class,()->uploads.start(dataset,"QUOTA","oversize-123",bad));
        for(int i=0;i<3;i++)start(new byte[]{1},"QUOTA","quota-123-"+i);
        assertThrows(AppException.class,()->start(new byte[]{1},"QUOTA","quota-123-4"));
    }
}
