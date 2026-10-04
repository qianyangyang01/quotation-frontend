-- Additive inbox state only. Never update saved quotation/review payloads or their versions.
CREATE TABLE quotation_review_notification (
    record_id uuid PRIMARY KEY REFERENCES quotation_record(id) ON DELETE CASCADE,
    owner_account varchar(24) NOT NULL,
    event_id uuid NOT NULL,
    read_event_id uuid,
    review_version bigint NOT NULL,
    kind varchar(16) NOT NULL,
    status varchar(16) NOT NULL,
    note varchar(500) NOT NULL,
    actor_name varchar(120) NOT NULL,
    quote_no varchar(40) NOT NULL,
    customer_name text NOT NULL,
    primary_sku text NOT NULL,
    occurred_at timestamptz NOT NULL,
    obsolete boolean NOT NULL DEFAULT false
);
CREATE INDEX quotation_review_notification_unread_idx ON quotation_review_notification(owner_account,occurred_at DESC)
    WHERE NOT obsolete AND (read_event_id IS NULL OR read_event_id<>event_id);

-- Existing active conclusions become visible once, including legacy reviews.
INSERT INTO quotation_review_notification(record_id,owner_account,event_id,review_version,kind,status,note,actor_name,quote_no,customer_name,primary_sku,occurred_at)
SELECT q.id,q.owner_account,md5('review-notification:'||q.id::text)::uuid,coalesce(r.version,0),'complete',
    coalesce(r.status,q.payload->>'financeReviewStatus'),
    left(coalesce(coalesce(r.state,q.payload)->>'financeReviewNote',''),500),
    left(coalesce(coalesce(r.state,q.payload)->>'financeReviewedBy','历史审核人'),120),
    q.quote_no,coalesce(q.payload->>'customerName',''),coalesce(q.payload->>'primarySku',''),
    CASE WHEN pg_input_is_valid(coalesce(r.state,q.payload)->>'financeReviewedAt','timestamp with time zone')
      THEN (coalesce(r.state,q.payload)->>'financeReviewedAt')::timestamptz ELSE q.updated_at END
FROM quotation_record q LEFT JOIN quotation_review r ON r.id=q.id
WHERE q.lifecycle_state='active' AND coalesce(r.status,q.payload->>'financeReviewStatus') IN ('approved','rejected','channel-exempt');
