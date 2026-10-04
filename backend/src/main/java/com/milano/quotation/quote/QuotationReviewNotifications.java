package com.milano.quotation.quote;

import com.milano.quotation.common.ApiResponse;
import com.milano.quotation.security.QuotationPrincipal;
import jakarta.persistence.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.*;

/** One latest message per quotation; reading it never changes the quotation or review. */
@Entity @Table(name="quotation_review_notification")
class QuotationReviewNotification {
    @Id @Column(name="record_id") UUID recordId;
    @Column(name="owner_account", nullable=false, length=24) String ownerAccount;
    @Column(name="event_id", nullable=false) UUID eventId;
    @Column(name="read_event_id") UUID readEventId;
    @Column(name="review_version", nullable=false) long reviewVersion;
    @Column(nullable=false, length=16) String kind;
    @Column(nullable=false, length=16) String status;
    @Column(nullable=false, length=500) String note;
    @Column(name="actor_name", nullable=false, length=120) String actorName;
    @Column(name="quote_no", nullable=false, length=40) String quoteNo;
    @Column(name="customer_name", nullable=false, columnDefinition="text") String customerName;
    @Column(name="primary_sku", nullable=false, columnDefinition="text") String primarySku;
    @Column(name="occurred_at", nullable=false) Instant occurredAt;
    @Column(nullable=false) boolean obsolete;
}
interface QuotationReviewNotificationRepository extends JpaRepository<QuotationReviewNotification,UUID> {
    @Query("select n from QuotationReviewNotification n, QuotationRecordEntity q where n.recordId=q.id and n.ownerAccount=:account and q.ownerAccount=:account and q.lifecycleState='active' and n.obsolete=false and (n.readEventId is null or n.readEventId<>n.eventId) order by n.occurredAt desc,n.recordId")
    List<QuotationReviewNotification> unread(String account);
    @Modifying @Query("update QuotationReviewNotification n set n.readEventId=:event where n.recordId=:id and n.ownerAccount=:account and n.eventId=:event")
    int acknowledge(UUID id, UUID event, String account);
    @Modifying @Query("update QuotationReviewNotification n set n.readEventId=n.eventId where n.ownerAccount=:account and n.eventId in :events")
    int acknowledgeBatch(String account, List<UUID> events);
}

@Service
class QuotationReviewNotifications {
    private final QuotationReviewNotificationRepository notifications;
    QuotationReviewNotifications(QuotationReviewNotificationRepository notifications) {
        this.notifications=notifications;
    }
    // Caller holds the quotation lock. Review changes and delivery commit or roll back together.
    void publish(QuotationRecordEntity quote, QuotationReviewEntity review, String action, String note, QuotationPrincipal actor) {
        var n=notifications.findById(quote.id).orElseGet(QuotationReviewNotification::new);
        n.recordId=quote.id; n.ownerAccount=quote.ownerAccount; n.eventId=UUID.randomUUID();
        n.reviewVersion=review.version; n.kind=action; n.status=review.status; n.note=note;
        n.actorName=actor.displayName(); n.occurredAt=Instant.now(); n.obsolete=false;
        n.quoteNo=quote.quoteNo;n.customerName=quote.payload.path("customerName").asText("");n.primarySku=quote.payload.path("primarySku").asText("");
        notifications.saveAndFlush(n);
    }
    void invalidate(UUID recordId) {
        notifications.findById(recordId).ifPresent(n->{n.obsolete=true;notifications.saveAndFlush(n);});
    }
    record Unread(UUID recordId, UUID eventId, long reviewVersion, String status) {}
    record Message(UUID recordId, UUID eventId, long reviewVersion, String kind, String status, String note,
                   String actorName, Instant occurredAt, String quoteNo, String customerName, String primarySku) {}
    record Inbox(List<Message> items, List<Unread> unread, Map<String,Long> counts, int total, int page, int totalPages) {}
    @Transactional(readOnly=true, isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Inbox inbox(String account, int page) {
        var rows=notifications.unread(account);
        int pages=(rows.size()+19)/20, safePage=Math.max(0,Math.min(page,Math.max(0,pages-1)));
        var visible=rows.stream().skip((long)safePage*20).limit(20).toList();
        var counts=new HashMap<String,Long>();
        rows.forEach(n->counts.merge(n.status,1L,Long::sum));
        var items=visible.stream().map(n->new Message(n.recordId,n.eventId,n.reviewVersion,n.kind,n.status,n.note,n.actorName,n.occurredAt,n.quoteNo,n.customerName,n.primarySku)).toList();
        return new Inbox(items,rows.stream().map(n->new Unread(n.recordId,n.eventId,n.reviewVersion,n.status)).toList(),counts,rows.size(),safePage,pages);
    }
    @Transactional public void acknowledge(String account, UUID id, UUID event) {
        // A stale acknowledgement cannot consume a newer message; other accounts are always a no-op.
        notifications.acknowledge(id,event,account);
    }
    @Transactional public void acknowledgeBatch(String account, List<UUID> events) {
        // Only the events present when the user clicked are acknowledged, never later arrivals.
        notifications.acknowledgeBatch(account,events);
    }
}

@RestController @RequestMapping("/api/v1/review-notifications")
@PreAuthorize("hasAnyAuthority('PERM_myRecords','PERM_allRecords')")
class QuotationReviewNotificationController {
    private final QuotationReviewNotifications notifications;
    QuotationReviewNotificationController(QuotationReviewNotifications notifications) { this.notifications=notifications; }
    @GetMapping ApiResponse<QuotationReviewNotifications.Inbox> inbox(@RequestParam(defaultValue="0") int page, Authentication auth) {
        return ApiResponse.ok(notifications.inbox(((QuotationPrincipal)auth.getPrincipal()).account(),page));
    }
    record ReadRequest(@jakarta.validation.constraints.NotNull UUID eventId) {}
    record BatchReadRequest(@jakarta.validation.constraints.NotEmpty @jakarta.validation.constraints.Size(max=1000) List<@jakarta.validation.constraints.NotNull UUID> eventIds) {}
    @PostMapping("/read-batch") ApiResponse<Void> readBatch(@jakarta.validation.Valid @RequestBody BatchReadRequest request, Authentication auth) {
        notifications.acknowledgeBatch(((QuotationPrincipal)auth.getPrincipal()).account(),request.eventIds());
        return ApiResponse.ok(null);
    }
    @PostMapping("/{id}/read") ApiResponse<Void> read(@PathVariable UUID id, @jakarta.validation.Valid @RequestBody ReadRequest request, Authentication auth) {
        notifications.acknowledge(((QuotationPrincipal)auth.getPrincipal()).account(),id,request.eventId());
        return ApiResponse.ok(null);
    }
}
