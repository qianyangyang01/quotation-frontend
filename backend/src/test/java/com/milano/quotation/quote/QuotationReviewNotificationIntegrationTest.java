package com.milano.quotation.quote;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Includes the established review concurrency/authorization scenarios from the superclass. */
class QuotationReviewNotificationIntegrationTest extends QuotationFinanceReviewIntegrationTest {
    @Autowired QuotationReviewNotificationRepository notifications;
    @Autowired QuotationReviewNotifications inbox;
    @Test void bulkReadIsScopedVersionSafeAndPreservesQuoteHistory() throws Exception {
        var a=record();claim(a);complete(a);var first=notifications.findById(a.id).orElseThrow().eventId;
        var b=record();claim(b);complete(b);var second=notifications.findById(b.id).orElseThrow().eventId;
        var before=view(b);var history=reviews.findById(b.id).orElseThrow().state.deepCopy();
        var foreign=record();foreign.ownerAccount="O"+owner;records.saveAndFlush(foreign);claim(foreign);complete(foreign);
        var foreignEvent=notifications.findById(foreign.id).orElseThrow().eventId;
        action(a,finance,qv(a),rv(a),"comment",null,"批量标记期间的新意见").andExpect(status().isOk());
        var body=mapper.writeValueAsString(java.util.Map.of("eventIds",java.util.List.of(first,second,foreignEvent)));
        mvc.perform(post("/api/v1/review-notifications/read-batch").with(employee).contentType("application/json").content(body)).andExpect(status().isForbidden());
        for(int i=0;i<2;i++)mvc.perform(post("/api/v1/review-notifications/read-batch").with(employee).with(csrf()).contentType("application/json").content(body)).andExpect(status().isOk());
        assertEquals(1,inbox.inbox(owner,0).total());assertEquals(a.id,inbox.inbox(owner,0).items().getFirst().recordId());
        assertEquals(1,inbox.inbox("O"+owner,0).total());assertEquals(before,view(b));assertEquals(history,reviews.findById(b.id).orElseThrow().state);
    }
    @Test void bulkReadRejectsInvalidInputAndUnauthorizedRoles() throws Exception {
        for(var body:java.util.List.of("{}","{\"eventIds\":[]}","{\"eventIds\":[null]}"))
            mvc.perform(post("/api/v1/review-notifications/read-batch").with(employee).with(csrf()).contentType("application/json").content(body)).andExpect(status().isUnprocessableEntity());
        var body=mapper.writeValueAsString(java.util.Map.of("eventIds",java.util.Collections.nCopies(1001,UUID.randomUUID())));
        mvc.perform(post("/api/v1/review-notifications/read-batch").with(employee).with(csrf()).contentType("application/json").content(body)).andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/api/v1/review-notifications/read-batch").with(purchase).with(csrf()).contentType("application/json").content("{\"eventIds\":[\""+UUID.randomUUID()+"\"]}")).andExpect(status().isForbidden());
    }
    @Test void spotChecksDoNotGenerateOrConsumeReviewNotifications() throws Exception {
        var r=record();claim(r);complete(r);var before=notifications.findById(r.id).orElseThrow();
        spotCheck(r,admin,qv(r)).andExpect(status().isOk());var after=notifications.findById(r.id).orElseThrow();
        assertEquals(before.eventId,after.eventId);assertEquals(before.reviewVersion,after.reviewVersion);
        assertEquals(1,inbox.inbox(owner,0).total());read(r.id,before.eventId);assertEquals(0,inbox.inbox(owner,0).total());
        assertTrue(view(r).path("spotChecked").asBoolean());
        setSpotCheck(r,admin,false,rv(r)).andExpect(status().isOk());assertFalse(view(r).path("spotChecked").asBoolean());
        var cancelled=notifications.findById(r.id).orElseThrow();assertEquals(before.eventId,cancelled.eventId);assertEquals(before.reviewVersion,cancelled.reviewVersion);assertEquals(0,inbox.inbox(owner,0).total());
    }
    @Test void deliveryIsPrivateAndReadingPreservesEveryBusinessAndReviewField() throws Exception {
        var r=record();claim(r);complete(r);
        var before=view(r);var history=reviews.findById(r.id).orElseThrow().state.deepCopy();
        var event=notifications.findById(r.id).orElseThrow().eventId;
        mvc.perform(get("/api/v1/review-notifications").with(employee)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.items[0].recordId").value(r.id.toString()));
        mvc.perform(get("/api/v1/review-notifications").with(other)).andExpect(jsonPath("$.data.total").value(0));
        mvc.perform(get("/api/v1/review-notifications").with(finance)).andExpect(jsonPath("$.data.total").value(0));
        mvc.perform(get("/api/v1/review-notifications").with(purchase)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/review-notifications/{id}/read",r.id).with(other).with(csrf()).contentType("application/json").content("{\"eventId\":\""+event+"\"}")).andExpect(status().isOk());
        assertEquals(1,inbox.inbox(owner,0).total());
        mvc.perform(post("/api/v1/review-notifications/{id}/read",r.id).with(employee).contentType("application/json").content("{\"eventId\":\""+event+"\"}")).andExpect(status().isForbidden());
        read(r.id,event);read(r.id,event);
        assertEquals(0,inbox.inbox(owner,0).total());assertEquals(before,view(r));
        assertEquals(history,reviews.findById(r.id).orElseThrow().state);
        assertEquals(r.payload,records.findById(r.id).orElseThrow().payload);
    }
    @Test void staleReadCannotConsumeNewOpinionAndMessagesAreCoalescedPerQuote() throws Exception {
        var r=record();claim(r);complete(r);var first=notifications.findById(r.id).orElseThrow().eventId;
        action(r,finance,qv(r),rv(r),"comment",null,"请核实包装尺寸").andExpect(status().isOk());
        var next=notifications.findById(r.id).orElseThrow();assertNotEquals(first,next.eventId);
        read(r.id,first);assertEquals(1,inbox.inbox(owner,0).total());assertEquals("comment",inbox.inbox(owner,0).items().getFirst().kind());
        assertEquals(rv(r),next.reviewVersion);read(r.id,next.eventId);assertEquals(0,inbox.inbox(owner,0).total());
    }
    @Test void invalidationHidesOldConclusionsAndFailedReviewDoesNotDeliver() throws Exception {
        var r=record();claim(r);
        action(r,finance,qv(r),rv(r)+1,"complete","approved","").andExpect(status().isConflict());
        assertEquals(0,inbox.inbox(owner,0).total());complete(r);assertEquals(1,inbox.inbox(owner,0).total());
        price(r,7);assertEquals(0,inbox.inbox(owner,0).total());assertTrue(notifications.findById(r.id).orElseThrow().obsolete);
        claim(r);complete(r);assertEquals(1,inbox.inbox(owner,0).total());
        var saved=records.findById(r.id).orElseThrow();saved.lifecycleState="archived";records.saveAndFlush(saved);
        assertEquals(0,inbox.inbox(owner,0).total());
    }
    @Test void inboxPaginatesWhileCountsCoverAllUnreadRecords() throws Exception {
        for(int i=0;i<23;i++){var r=record();action(r,finance,qv(r),rv(r),"comment",null,"待确认 "+i).andExpect(status().isOk());}
        var first=inbox.inbox(owner,0);var last=inbox.inbox(owner,999);
        assertEquals(23,first.total());assertEquals(23,first.unread().size());assertEquals(20,first.items().size());
        assertEquals(1,last.page());assertEquals(3,last.items().size());assertEquals(23L,first.counts().get("pending"));
    }
    void read(UUID id,UUID event) throws Exception {
        mvc.perform(post("/api/v1/review-notifications/{id}/read",id).with(employee).with(csrf()).contentType("application/json").content("{\"eventId\":\""+event+"\"}")).andExpect(status().isOk());
    }
}
