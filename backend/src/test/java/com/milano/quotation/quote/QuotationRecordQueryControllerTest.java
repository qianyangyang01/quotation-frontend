package com.milano.quotation.quote;

import org.junit.jupiter.api.Test;
import com.milano.quotation.security.QuotationPrincipal;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.access.AccessDeniedException;
import tools.jackson.databind.node.JsonNodeFactory;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class QuotationRecordQueryControllerTest {
    private UsernamePasswordAuthenticationToken auth(String... permissions){var p=new QuotationPrincipal(UUID.randomUUID(),"ME","本人","hash","employee",true,false,List.of(permissions));return new UsernamePasswordAuthenticationToken(p,"",p.getAuthorities());}
    @Test void companyScopeCannotExpandPersonalPermissionAndDeepLinksAreProtected(){
        var repo=mock(QuotationRecordRepository.class);var query=mock(QuotationRecordQuery.class);
        var controller=new QuotationController(repo,null,null,null,query,null,null,mock(QuotationReviewService.class));
        controller.search("company",0,10,"","","","",null,null,auth("myRecords"));verify(query).search(eq("ME"),any(),eq(0),eq(10));
        controller.search("company",0,10,"","","","",null,null,auth("allRecords"));verify(query).search(isNull(),any(),eq(0),eq(10));
        clearInvocations(query);controller.search("mine",0,10,"","","","",null,null,auth("allRecords"));verify(query).search(eq("ME"),any(),eq(0),eq(10));
        var row=new QuotationRecordEntity();row.id=UUID.randomUUID();row.ownerAccount="OTHER";row.payload=JsonNodeFactory.instance.objectNode().put("id",row.id.toString());when(repo.findById(row.id)).thenReturn(Optional.of(row));
        assertThrows(AccessDeniedException.class,()->controller.get(row.id,auth("myRecords")));
        assertEquals(row.id.toString(),controller.get(row.id,auth("allRecords")).data().path("id").asText());
    }
}
