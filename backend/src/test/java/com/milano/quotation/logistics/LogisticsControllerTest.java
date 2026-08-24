package com.milano.quotation.logistics;

import com.milano.quotation.audit.AuditService;
import com.milano.quotation.common.AppException;
import com.milano.quotation.idempotency.IdempotencyService;
import com.milano.quotation.security.QuotationPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LogisticsControllerTest {
    private LogisticsService logistics; private LogisticsWorkbookService workbooks; private IdempotencyService idempotency; private AuditService audit; private LogisticsController controller;
    private final UUID providerId=UUID.randomUUID();

    @BeforeEach void setup(){logistics=mock(LogisticsService.class);workbooks=mock(LogisticsWorkbookService.class);idempotency=mock(IdempotencyService.class);audit=mock(AuditService.class);controller=new LogisticsController(logistics,mock(LogisticsQueryService.class),workbooks,audit,idempotency);when(idempotency.existing(anyString(),anyString(),anyString(),any())).thenReturn(Optional.empty());}

    @Test void globalPreviewKeepsFileLevelProviderAndParserFailuresSeparate(){
        var unmatched=file("未知渠道.xlsx",new byte[]{1});var broken=file("燕文坏文件.xlsx",new byte[]{2});
        when(logistics.matchGlobalProvider("未知渠道.xlsx")).thenReturn(JsonNodeFactory.instance.objectNode().put("providerMatchStatus","unmatched").put("providerMatchMessage","未匹配物流商"));
        when(logistics.matchGlobalProvider("燕文坏文件.xlsx")).thenReturn(JsonNodeFactory.instance.objectNode().put("providerMatchStatus","matched").put("providerId",providerId.toString()).put("providerName","燕文"));
        when(workbooks.parse(eq(broken),any())).thenThrow(new RuntimeException("第4行国家代码错误"));
        var result=(ObjectNode)controller.previewGlobalImports(List.of(unmatched,broken)).data();
        assertEquals(2,result.path("blocking").asInt());assertEquals("unmatched",result.path("items").get(0).path("providerMatchStatus").asText());assertEquals(1,result.path("items").get(1).path("errors").asInt());assertTrue(result.path("items").get(1).path("issues").get(0).path("message").asText().contains("第4行"));
    }

    @Test void previewMarksSameChannelFilesAsIndependentBlockersAndUsesIndexHashKeys(){
        var first=file("燕文化妆品.xlsx",new byte[]{1,2});var second=file("燕文化妆品-价格更新.xlsx",new byte[]{3,4});
        when(logistics.matchGlobalProvider(anyString())).thenReturn(JsonNodeFactory.instance.objectNode().put("providerMatchStatus","matched").put("providerId",providerId.toString()).put("providerName","燕文"));
        when(workbooks.parse(any(),any())).thenAnswer(call->parsed(((MockMultipartFile)call.getArgument(0)).getOriginalFilename(),UUID.randomUUID().toString()));
        when(logistics.matchProviderFile(eq(providerId),anyString(),anyString())).thenReturn(JsonNodeFactory.instance.objectNode().put("action","match").put("channelId",UUID.randomUUID().toString()).put("channelName","燕文化妆品").put("channelCode","YW-COS").put("hasDraft",true));
        var shared=UUID.randomUUID().toString();when(logistics.matchProviderFile(eq(providerId),anyString(),anyString())).thenReturn(JsonNodeFactory.instance.objectNode().put("action","match").put("channelId",shared).put("channelName","燕文化妆品").put("channelCode","YW-COS").put("hasDraft",true));when(logistics.publishedRows(UUID.fromString(shared))).thenReturn(JsonNodeFactory.instance.arrayNode());
        var result=(ObjectNode)controller.previewGlobalImports(List.of(first,second)).data();
        assertEquals(2,result.path("blocking").asInt(),result.toPrettyString());assertNotEquals(result.path("items").get(0).path("fileKey").asText(),result.path("items").get(1).path("fileKey").asText());assertEquals(0,result.path("replaceDrafts").asInt());
    }

    @Test void archiveIsIdempotentAndWritesDraftTerminationAudit(){
        var channelId=UUID.randomUUID();var body=JsonNodeFactory.instance.objectNode().put("reason","业务下线");var archived=JsonNodeFactory.instance.objectNode().put("id",channelId.toString()).put("rejectedDrafts",1);archived.putArray("rejectedVersionIds").add(UUID.randomUUID().toString());when(logistics.archiveChannel(channelId,"业务下线","ADMIN",4)).thenReturn(archived);
        var response=controller.archiveChannel(channelId,body,4,"archive-key-123",auth());assertSame(archived,response.data());verify(audit).record(eq("logistics.channel-archive"),anyString(),eq(channelId.toString()),eq("success"),any());verify(audit).record(eq("logistics.draft-terminate"),anyString(),anyString(),eq("success"),any());
        when(idempotency.existing(eq("ADMIN"),eq("logistics-channel-archive"),eq("archive-key-123"),any())).thenReturn(Optional.of(archived));controller.archiveChannel(channelId,JsonNodeFactory.instance.objectNode().put("reason","业务下线"),4,"archive-key-123",auth());verify(logistics,times(1)).archiveChannel(any(),anyString(),anyString(),anyLong());
    }

    @Test void rejectsEmptyOversizedAndExcessiveGlobalBatchesBeforeParsing(){
        assertThrows(AppException.class,()->controller.previewGlobalImports(null));
        assertThrows(AppException.class,()->controller.previewGlobalImports(List.of()));
        var small=sizedFile("燕文普货.xlsx",1);
        assertThrows(AppException.class,()->controller.previewGlobalImports(java.util.Collections.nCopies(101,small)));
        assertThrows(AppException.class,()->controller.previewGlobalImports(List.of(sizedFile("燕文超大.xlsx",100L*1024*1024+1))));
        var ninetyMb=sizedFile("燕文批量.xlsx",90L*1024*1024);
        assertThrows(AppException.class,()->controller.previewGlobalImports(java.util.Collections.nCopies(6,ninetyMb)));
        verifyNoInteractions(workbooks);
    }

    @Test void unreadableBlockedFileGetsStableFallbackKeyAndDefaultMessage() throws Exception {
        var unreadable=mock(org.springframework.web.multipart.MultipartFile.class);
        when(unreadable.getOriginalFilename()).thenReturn("燕文损坏.xlsx");
        when(unreadable.getSize()).thenReturn(12L);
        when(unreadable.getBytes()).thenThrow(new java.io.IOException("read failed"));
        when(logistics.matchGlobalProvider("燕文损坏.xlsx")).thenReturn(JsonNodeFactory.instance.objectNode().put("providerMatchStatus","matched").put("providerId",providerId.toString()));
        when(workbooks.parse(eq(unreadable),any())).thenThrow(new RuntimeException((String)null));
        var result=(ObjectNode)controller.previewGlobalImports(List.of(unreadable)).data();
        assertEquals("0:unreadable-12",result.path("items").get(0).path("fileKey").asText());
        assertEquals("文件预检失败",result.path("items").get(0).path("issues").get(0).path("message").asText());
    }

    @Test void parserFailureStillShowsProviderChannelAndExistingDraftState(){
        var broken=file("燕文化妆品.xlsx",new byte[]{7,8,9});var channelId=UUID.randomUUID();
        when(logistics.matchGlobalProvider("燕文化妆品.xlsx")).thenReturn(JsonNodeFactory.instance.objectNode().put("providerMatchStatus","matched").put("providerId",providerId.toString()).put("providerName","燕文"));
        when(workbooks.parse(eq(broken),any())).thenThrow(new RuntimeException("第4行国家代码错误"));
        when(logistics.matchProviderFile(eq(providerId),eq("燕文化妆品.xlsx"),anyString())).thenReturn(JsonNodeFactory.instance.objectNode().put("action","match").put("channelId",channelId.toString()).put("channelName","燕文化妆品").put("hasDraft",true));
        var item=((ObjectNode)controller.previewGlobalImports(List.of(broken)).data()).path("items").get(0);
        assertEquals("matched",item.path("providerMatchStatus").asText());assertEquals(channelId.toString(),item.path("channelId").asText());assertTrue(item.path("hasDraft").asBoolean());assertEquals(1,item.path("errors").asInt());
    }

    @Test void idempotentGlobalRetryReturnsBeforeDynamicPreviewChanges(){
        var file=file("燕文化妆品.xlsx",new byte[]{1,3,5});var saved=JsonNodeFactory.instance.objectNode().put("count",1);
        when(idempotency.existing(eq("ADMIN"),eq("logistics-global-batch-import"),eq("retry-key-123"),any())).thenReturn(Optional.of(saved));
        var result=controller.importGlobalFiles(List.of(file),false,"retry-key-123",auth());
        assertSame(saved,result.data());verifyNoInteractions(logistics,workbooks);verify(idempotency,never()).save(anyString(),anyString(),anyString(),any(),any());
    }

    private UsernamePasswordAuthenticationToken auth(){var principal=new QuotationPrincipal(UUID.randomUUID(),"ADMIN","管理员","x","super_admin",true,false,List.of("logistics"));return UsernamePasswordAuthenticationToken.authenticated(principal,"x",principal.getAuthorities());}
    private static MockMultipartFile file(String name,byte[] content){return new MockMultipartFile("files",name,"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",content);}
    private static org.springframework.web.multipart.MultipartFile sizedFile(String name,long size){var file=mock(org.springframework.web.multipart.MultipartFile.class);when(file.getOriginalFilename()).thenReturn(name);when(file.getSize()).thenReturn(size);return file;}
    private static ObjectNode parsed(String name,String hash){var value=JsonNodeFactory.instance.objectNode().put("fileName",name).put("sourceHash",hash).put("validRows",1).put("errors",0).put("warnings",0);value.putArray("rows");value.putArray("issues");value.putArray("diffRows");value.putObject("summary").put("added",1).put("price",0).put("rule",0).put("removed",0).put("unchanged",0).put("highRisk",0);return value;}
}
