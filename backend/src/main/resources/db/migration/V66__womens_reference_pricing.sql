-- Upgrade current policy only. Saved quotes, reviews, drafts and deal states are never updated.
-- Keep finance's existing tiers and enabled state, and invalidate stale pending quotes by version.
WITH previous AS MATERIALIZED (
    SELECT setting_key, payload, version FROM finance_setting
    WHERE setting_key='womens-pricing' AND payload->>'schemaVersion'='1'
      AND payload->>'basis'='total-cost-usd' FOR UPDATE
), upgraded AS (
    UPDATE finance_setting f SET payload=p.payload || '{"schemaVersion":2,"basis":"reference-price-usd","referenceCoefficient":1.23}'::jsonb,
        version=p.version+1, updated_at=now()
    FROM previous p WHERE f.setting_key=p.setting_key
    RETURNING f.setting_key,f.payload,f.version,p.payload AS old_payload,p.version AS old_version
)
INSERT INTO audit_log(id,request_id,actor_account,action,resource_type,resource_id,outcome,detail,created_at)
SELECT gen_random_uuid(),'migration-v66-womens-reference','system','finance.update','finance-setting',setting_key,'success',
    jsonb_build_object('before',old_payload::text,'after',payload::text,'previousVersion',old_version,'version',version,
      'reason','女装改为综合成本乘1.23参考系数选档；历史报价保持原快照','migration','V66'),now()
FROM upgraded;
