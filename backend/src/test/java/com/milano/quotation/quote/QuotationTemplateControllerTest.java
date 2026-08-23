package com.milano.quotation.quote;

import com.milano.quotation.audit.AuditService;
import com.milano.quotation.common.AppException;
import com.milano.quotation.idempotency.IdempotencyService;
import com.milano.quotation.security.QuotationPrincipal;
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
    @BeforeEach void setup(){templates=mock(QuotationTemplateRepository.class);audit=mock(AuditService.class);idempotency=mock(IdempotencyService.class);controller=new QuotationTemplateController(templates,audit,idempotency);var principal=new QuotationPrincipal(UUID.randomUUID(),"ADMIN","管理员","hash","superadmin",true,false,List.of("quote"));auth=new UsernamePasswordAuthenticationToken(principal,"",principal.getAuthorities());}

    @Test void listsAndCreatesValidatedTemplatesWithIdempotency(){var existing=JsonNodeFactory.instance.objectNode().put("id","cached");when(idempotency.existing(eq("ADMIN"),eq("quotation-template-create"),eq("key-0001"),any())).thenReturn(Optional.of(existing));assertSame(existing,controller.create(JsonNodeFactory.instance.objectNode().put("name","模板"),"key-0001",auth).data());verify(templates,never()).save(any());when(idempotency.existing(anyString(),anyString(),eq("key-0002"),any())).thenReturn(Optional.empty());assertThrows(AppException.class,()->controller.create(JsonNodeFactory.instance.objectNode().put("name",""),"key-0002",auth));assertThrows(AppException.class,()->controller.create(JsonNodeFactory.instance.objectNode().put("name","x".repeat(121)),"key-0002",auth));when(templates.save(any())).thenAnswer(c->c.getArgument(0));var created=controller.create(JsonNodeFactory.instance.objectNode().put("name"," 正式模板 "),"key-0002",auth).data();assertEquals("正式模板",created.path("name").asText());verify(idempotency).save(eq("ADMIN"),eq("quotation-template-create"),eq("key-0002"),any(),any());var row=row("ADMIN","列表模板");when(templates.findByOwnerAccountOrderByUpdatedAtDesc("ADMIN")).thenReturn(List.of(row));assertEquals(1,controller.list(auth).data().size());}

    @Test void updatesOnlyOwnedCurrentVersionAndValidName(){var id=UUID.randomUUID();when(templates.findById(id)).thenReturn(Optional.empty());assertThrows(AppException.class,()->controller.update(id,JsonNodeFactory.instance.objectNode(),auth));var foreign=row("OTHER","模板");foreign.id=id;when(templates.findById(id)).thenReturn(Optional.of(foreign));assertThrows(AccessDeniedException.class,()->controller.update(id,JsonNodeFactory.instance.objectNode(),auth));var owned=row("ADMIN","模板");owned.id=id;owned.version=4;when(templates.findById(id)).thenReturn(Optional.of(owned));assertThrows(AppException.class,()->controller.update(id,JsonNodeFactory.instance.objectNode().put("name","新模板"),auth));assertThrows(AppException.class,()->controller.update(id,JsonNodeFactory.instance.objectNode().put("name","新模板").put("_version",3),auth));assertThrows(AppException.class,()->controller.update(id,JsonNodeFactory.instance.objectNode().put("name","").put("_version",4),auth));when(templates.saveAndFlush(any())).thenAnswer(c->c.getArgument(0));var updated=controller.update(id,JsonNodeFactory.instance.objectNode().put("name","新模板").put("description","说明").put("_version",4),auth).data();assertEquals("新模板",updated.path("name").asText());assertFalse(owned.payload.has("_version"));}

    @Test void deletesOnlyOwnedTemplate(){var row=row("ADMIN","模板");when(templates.findById(row.id)).thenReturn(Optional.of(row));assertNull(controller.delete(row.id,auth).data());verify(templates).delete(row);verify(audit).record(eq("template.delete"),eq("quotation-template"),eq(row.id.toString()),eq("success"),any());}
    private QuotationTemplateEntity row(String owner,String name){var row=new QuotationTemplateEntity();row.id=UUID.randomUUID();row.ownerAccount=owner;row.name=name;row.payload=JsonNodeFactory.instance.objectNode().put("name",name);row.createdAt=Instant.now();row.updatedAt=row.createdAt;return row;}
}
