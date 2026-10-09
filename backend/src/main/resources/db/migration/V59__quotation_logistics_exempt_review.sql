-- Manual conclusion for the current quotation: logistics exempt, purchase reviewed.
-- Extend the allowed states only; preserve all quotation snapshots and review history.
ALTER TABLE quotation_review DROP CONSTRAINT quotation_review_status_check;
ALTER TABLE quotation_review ADD CONSTRAINT quotation_review_status_check
    CHECK (status IN ('pending', 'reviewing', 'approved', 'rejected', 'channel-exempt', 'logistics-exempt'));
