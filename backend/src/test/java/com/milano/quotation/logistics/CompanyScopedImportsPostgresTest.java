package com.milano.quotation.logistics;

import com.milano.quotation.storage.AssetStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"spring.flyway.enabled=true","spring.jpa.hibernate.ddl-auto=validate",
    "spring.jpa.defer-datasource-initialization=false","spring.sql.init.mode=never","spring.session.store-type=none",
    "app.storage.initialize=false","app.logistics.resume-on-start=false","app.logistics.file-cleanup-enabled=false"})
@ActiveProfiles("test") @DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers(disabledWithoutDocker=true)
class CompanyScopedImportsPostgresTest {
    @Container static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16.4-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",postgres::getJdbcUrl);r.add("spring.datasource.username",postgres::getUsername);r.add("spring.datasource.password",postgres::getPassword);
    }
    @Autowired CompanyScopedImports scoped;
    @Autowired LogisticsVersionRepository versions;
    @Autowired LogisticsService logistics;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper mapper;
    @MockitoBean LogisticsImportService imports;
    @MockitoBean CompanyChannelService directory;
    @MockitoBean AssetStorageService storage;

    @Test void channelUploadReadsItsPendingJpaDraftBeforeTransactionCommit(){
        var dataset=jdbc.sql("select logistics_active_dataset()").query(UUID.class).single();
        when(directory.importDataset()).thenReturn(dataset);
        var provider=logistics.addProvider(mapper.createObjectNode().put("name","QA provider").put("code","QA-PROVIDER").put("enabled",false));
        var channel=logistics.addChannel(mapper.createObjectNode().put("providerId",provider.path("id").asText()).put("name","QA channel").put("code","QA-CHANNEL").put("enabled",false));
        var channelId=UUID.fromString(channel.path("id").asText());var id=UUID.randomUUID();var versionId=UUID.randomUUID();
        var files=List.of(new MockMultipartFile("file","new-prices.xlsx","application/octet-stream",new byte[]{1}));
        when(imports.uploadSelected(eq(dataset),anyList(),eq("QA"),eq("test-key"),eq(false),isNull(),eq(channelId)))
            .thenReturn(mapper.createObjectNode().put("id",id.toString()));
        doAnswer(invocation->{
            var version=new LogisticsVersionEntity();version.id=versionId;version.channelId=channelId;version.versionNumber=1;
            version.status="draft";version.sourceHash="qa-hash";version.createdAt=Instant.now();
            var payload=mapper.createObjectNode().put("id",versionId.toString()).put("status","draft");payload.putArray("rows");
            version.payload=payload;versions.save(version);return null;
        }).when(imports).process(id);
        var batch=mapper.createObjectNode();batch.putObject("payload").putArray("results").addObject().put("versionId",versionId.toString()).put("status","draft");
        when(imports.get(id)).thenReturn(batch);
        var result=scoped.upload(null,channelId,new ArrayList<>(files),"QA","test-key",false);
        assertEquals(versionId.toString(),result.path("id").asText());assertEquals("draft",result.path("status").asText());
        assertEquals(1,jdbc.sql("select count(*) from logistics_version where id=:id").param("id",versionId).query(Integer.class).single());
    }
}
