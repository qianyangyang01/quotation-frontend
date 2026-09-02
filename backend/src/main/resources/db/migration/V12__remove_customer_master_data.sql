UPDATE quotation_record AS quotation
SET payload = jsonb_set(quotation.payload, '{customerName}', to_jsonb(customer.name), true)
FROM customer
WHERE quotation.customer_id = customer.id
  AND (NOT quotation.payload ? 'customerName' OR btrim(COALESCE(quotation.payload ->> 'customerName', '')) = '');

UPDATE quotation_record
SET payload = payload - 'customerId'
WHERE payload ? 'customerId';

UPDATE quotation_draft
SET payload = payload - 'customerId'
WHERE payload ? 'customerId';

UPDATE idempotency_record
SET response_body = response_body - 'customerId'
WHERE operation = 'quotation-create'
  AND response_body ? 'customerId';

ALTER TABLE quotation_record DROP COLUMN customer_id;
DROP TABLE customer;
