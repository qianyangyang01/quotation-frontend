package com.milano.quotation.quote;

import com.milano.quotation.audit.AuditService;
import com.milano.quotation.common.AppException;
import com.milano.quotation.idempotency.IdempotencyService;
import com.milano.quotation.security.QuotationPrincipal;
import com.milano.quotation.purchase.PurchaseProductService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import tools.jackson.databind.node.JsonNodeFactory;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class QuotationTemplateControllerTest {
    private QuotationTemplateRepository templates;private AuditService audit;private IdempotencyService idempotency;private QuotationTemplateController controller;
    private UsernamePasswordAuthenticationToken auth;
    @BeforeEach void setup(){templates=mock(QuotationTemplateRepository.class);audit=mock(AuditService.class);idempotency=mock(IdempotencyService.class);controller=new QuotationTemplateController(templates,audit,idempotency,mock(PurchaseProductService.class));var principal=new QuotationPrincipal(UUID.randomUUID(),"ADMIN","管理员","hash","superadmin",true,false,List.of("quote"));auth=new UsernamePasswordAuthenticationToken(principal,"",principal.getAuthorities());}

    @Test void listsAndCreatesValidatedTemplatesWithIdempotency(){var existing=JsonNodeFactory.instance.objectNode().put("id","cached");when(idempotency.existing(eq("ADMIN"),eq("quotation-template-create"),eq("key-0001"),any())).thenReturn(Optional.of(existing));assertSame(existing,controller.create(JsonNodeFactory.instance.objectNode().put("name","模板"),"key-0001",auth).data());verify(templates,never()).save(any());when(idempotency.existing(anyString(),anyString(),eq("key-0002"),any())).thenReturn(Optional.empty());assertThrows(AppException.class,()->controller.create(JsonNodeFactory.instance.objectNode().put("name",""),"key-0002",auth));assertThrows(AppException.class,()->controller.create(JsonNodeFactory.instance.objectNode().put("name","x".repeat(121)),"key-0002",auth));when(templates.save(any())).thenAnswer(c->c.getArgument(0));var created=controller.create(JsonNodeFactory.instance.objectNode().put("name"," 正式模板 "),"key-0002",auth).data();assertEquals("正式模板",created.path("name").asText());verify(idempotency).save(eq("ADMIN"),eq("quotation-template-create"),eq("key-0002"),any(),any());var row=row("ADMIN","列表模板");when(templates.findByOwnerAccountOrderByUpdatedAtDesc("ADMIN")).thenReturn(List.of(row));assertEquals(1,controller.list(auth).data().size());}

    @Test void updatesOnlyOwnedCurrentVersionAndValidName(){var id=UUID.randomUUID();when(templates.findById(id)).thenReturn(Optional.empty());assertThrows(AppException.class,()->controller.update(id,JsonNodeFactory.instance.objectNode(),auth));var foreign=row("OTHER","模板");foreign.id=id;when(templates.findById(id)).thenReturn(Optional.of(foreign));assertThrows(AccessDeniedException.class,()->controller.update(id,JsonNodeFactory.instance.objectNode(),auth));var owned=row("ADMIN","模板");owned.id=id;owned.version=4;when(templates.findById(id)).thenReturn(Optional.of(owned));assertThrows(AppException.class,()->controller.update(id,JsonNodeFactory.instance.objectNode().put("name","新模板"),auth));assertThrows(AppException.class,()->controller.update(id,JsonNodeFactory.instance.objectNode().put("name","新模板").put("_version",3),auth));assertThrows(AppException.class,()->controller.update(id,JsonNodeFactory.instance.objectNode().put("name","").put("_version",4),auth));when(templates.saveAndFlush(any())).thenAnswer(c->c.getArgument(0));var updated=controller.update(id,JsonNodeFactory.instance.objectNode().put("name","新模板").put("description","说明").put("_version",4),auth).data();assertEquals("新模板",updated.path("name").asText());assertFalse(owned.payload.has("_version"));}

    @Test void deletesOnlyOwnedTemplate(){var row=row("ADMIN","模板");when(templates.findById(row.id)).thenReturn(Optional.of(row));assertNull(controller.delete(row.id,auth).data());verify(templates).delete(row);verify(audit).record(eq("template.delete"),eq("quotation-template"),eq(row.id.toString()),eq("success"),any());}
    @Test void rejectsLegacyOrMismatchedChannelConfirmationWithoutChangingTemplate(){
        var owned=row("ADMIN","化妆品");owned.version=4;
        when(templates.findById(owned.id)).thenReturn(Optional.of(owned));
        var body=JsonNodeFactory.instance.objectNode().put("_version",4);
        body.putArray("items").addObject().put("country","美国").put("transport","新渠道");
        assertThrows(AppException.class,()->controller.update(owned.id,body,auth));
        var confirmation=body.putObject("_confirmedUpdate").put("templateId",UUID.randomUUID().toString()).put("baseVersion",4).put("source","template-channel-confirmation");
        assertThrows(AppException.class,()->controller.update(owned.id,body,auth));
        confirmation.put("templateId",owned.id.toString()).put("baseVersion",3);
        assertThrows(AppException.class,()->controller.update(owned.id,body,auth));
        confirmation.put("baseVersion",4).put("source","unconfirmed");
        assertThrows(AppException.class,()->controller.update(owned.id,body,auth));
        confirmation.put("source","template-channel-confirmation");body.putArray("items");
        assertThrows(AppException.class,()->controller.update(owned.id,body,auth));
        assertFalse(owned.payload.has("items"));
        verify(templates,never()).saveAndFlush(any());verifyNoInteractions(audit);
    }
    @Test void savesConfirmedChannelsAndAuditsImmutableBeforeAfterWithoutPersistingClientMetadata(){
        var owned=row("ADMIN","化妆品");owned.version=4;
        ((tools.jackson.databind.node.ObjectNode)owned.payload).putArray("items").addObject().put("country","英国").put("transport","旧渠道");
        when(templates.findById(owned.id)).thenReturn(Optional.of(owned));
        when(templates.saveAndFlush(any())).thenAnswer(call->{owned.version++;return owned;});
        var body=JsonNodeFactory.instance.objectNode().put("_version",4).put("ownerAccount","OTHER");
        body.putArray("items").addObject().put("country","英国").put("transport","新渠道");
        body.putObject("_confirmedUpdate").put("templateId",owned.id.toString()).put("baseVersion",4).put("source","template-channel-confirmation");
        var updated=controller.update(owned.id,body,auth).data();
        assertEquals(5,updated.path("_version").asLong());
        assertEquals("新渠道",updated.path("items").get(0).path("transport").asText());
        assertFalse(owned.payload.has("_confirmedUpdate"));assertFalse(owned.payload.has("_version"));assertFalse(owned.payload.has("ownerAccount"));
        verify(audit).record(eq("template.update"),eq("quotation-template"),eq(owned.id.toString()),eq("success"),argThat(detail ->
            detail.get("source").equals("template-channel-confirmation") && detail.get("beforeVersion").equals(4L) && detail.get("afterVersion").equals(5L)
            && ((tools.jackson.databind.JsonNode)detail.get("before")).path("items").get(0).path("transport").asText().equals("旧渠道")
            && ((tools.jackson.databind.JsonNode)detail.get("after")).path("items").get(0).path("transport").asText().equals("新渠道")));
        assertThrows(AppException.class,()->controller.update(owned.id,body,auth));
        verify(templates,times(1)).saveAndFlush(any());
    }
    private QuotationTemplateEntity row(String owner,String name){var row=new QuotationTemplateEntity();row.id=UUID.randomUUID();row.ownerAccount=owner;row.name=name;row.payload=JsonNodeFactory.instance.objectNode().put("name",name);row.createdAt=Instant.now();row.updatedAt=row.createdAt;return row;}
}
