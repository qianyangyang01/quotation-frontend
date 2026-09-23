-- Keep workflow revisions separate from quotation prices, timestamps and business revisions.
CREATE TABLE quotation_review (
    id UUID PRIMARY KEY REFERENCES quotation_record(id),
    status VARCHAR(16) NOT NULL CHECK (status IN ('pending','reviewing','approved','rejected')),
    claimant_account VARCHAR(24),
    state JSONB NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CHECK ((status = 'reviewing') = (claimant_account IS NOT NULL))
);
CREATE INDEX idx_quotation_review_status_claimant ON quotation_review(status, claimant_account);
-- Preserve existing explicit conclusions; do not infer approval from confirmation or deal status.
INSERT INTO quotation_review (id,status,state)
SELECT id,payload->>'financeReviewStatus',jsonb_strip_nulls(jsonb_build_object(
    'financeReviewStatus',payload->>'financeReviewStatus',
    'financeReviewedAt',payload->>'financeReviewedAt',
    'financeReviewedBy',payload->>'financeReviewedBy',
    'financeReviewedAccount',payload->>'financeReviewedAccount'))
FROM quotation_record WHERE payload->>'financeReviewStatus' IN ('approved','rejected');
