-- Add a manual conclusion only. Existing reviews and quotation snapshots are unchanged.
ALTER TABLE quotation_review DROP CONSTRAINT quotation_review_status_check;
ALTER TABLE quotation_review ADD CONSTRAINT quotation_review_status_check
    CHECK (status IN ('pending', 'reviewing', 'approved', 'rejected', 'channel-exempt'));
