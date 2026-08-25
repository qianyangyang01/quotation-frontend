\set ON_ERROR_STOP on

-- This fixture is intentionally restricted to the isolated performance stack.
-- It never reads or copies production business data.
DO $$
BEGIN
  IF current_database() <> 'quotation_perf' THEN
    RAISE EXCEPTION 'Refusing to seed non-performance database: %', current_database();
  END IF;
END $$;

INSERT INTO purchase_product(id, sku, payload, version, catalog_state, quote_ready, created_at, updated_at)
SELECT (
    substr(md5('perf-product-' || value),1,8) || '-' || substr(md5('perf-product-' || value),9,4) || '-' ||
    substr(md5('perf-product-' || value),13,4) || '-' || substr(md5('perf-product-' || value),17,4) || '-' ||
    substr(md5('perf-product-' || value),21,12)
  )::uuid,
  'PERF-SKU-' || lpad(value::text, 5, '0'),
  jsonb_build_object(
    'sku', 'PERF-SKU-' || lpad(value::text, 5, '0'),
    'skuOrigin', 'manual',
    'category', (ARRAY['文胸','袜子','内裤','服装','化妆品','保健品','日用品','庭院工具','家用电器','健身器材','厨房用具','家纺','配饰','鞋','文具','灯具','数码','辅料','玩具','书籍','宠物用品','医疗','汽车用品','清洁用品','箱包','护肤品','其他'])[((value - 1) % 27) + 1],
    'quotationOwner', '性能测试采购', 'quotationDate', '2026-08-25',
    'weightG', 100 + (value % 900), 'lengthCm', 10 + (value % 20), 'widthCm', 10 + (value % 15), 'heightCm', 10 + (value % 10),
    'minOrderQty', 1, 'purchasePriceCny', 8 + ((value % 100)::numeric / 10),
    'tier2MinQty', 10, 'tier2PriceCny', 7 + ((value % 100)::numeric / 10),
    'tier3MinQty', 100, 'tier3PriceCny', 6 + ((value % 100)::numeric / 10),
    'singleFreightCny', 2, 'freight10Cny', 10, 'freight100Cny', 50,
    'stockStatus', CASE WHEN value % 11 = 0 THEN '待确认' ELSE '有货' END,
    'catalogState', 'ready', 'quoteReady', true, 'status', '资料完整'
  ), 0, 'ready', true, now(), now() - make_interval(secs => value)
FROM generate_series(1, 10000) AS value
ON CONFLICT (sku) DO NOTHING;

WITH source_user AS (
  SELECT '$2a$10$1NOnPrOuW1ElZG5ouBJXeO01jgBFgdWWiKEz7dnFbWyUC3wXNhsOO'::text AS password_hash
), users(account, display_name, role_key) AS (
  SELECT 'PERF' || lpad(value::text, 2, '0'), '性能员工' || value, 'employee' FROM generate_series(1,30) value
  UNION ALL VALUES
    ('PERFADMIN','性能管理员','super_admin'),
    ('PERFFIN','性能财务','finance'),
    ('PERFLOG','性能物流','logistics'),
    ('PERFPUR','性能采购','purchase')
)
INSERT INTO app_user(id, account, display_name, password_hash, role_key, status, must_change_password, password_updated_at, version, created_at, updated_at)
SELECT (
    substr(md5('perf-user-' || account),1,8) || '-' || substr(md5('perf-user-' || account),9,4) || '-' ||
    substr(md5('perf-user-' || account),13,4) || '-' || substr(md5('perf-user-' || account),17,4) || '-' ||
    substr(md5('perf-user-' || account),21,12)
  )::uuid,
  account, display_name, source_user.password_hash, role_key, 'enabled', false, now(), 0, now(), now()
FROM users CROSS JOIN source_user
ON CONFLICT (account) DO UPDATE SET
  password_hash = EXCLUDED.password_hash,
  role_key = EXCLUDED.role_key,
  status = EXCLUDED.status,
  must_change_password = false,
  updated_at = now();

INSERT INTO logistics_provider(id, code, payload, version, created_at, updated_at)
VALUES ('11111111-1111-4111-8111-111111111111', 'PERF-PROVIDER', '{"id":"11111111-1111-4111-8111-111111111111","name":"燕文","code":"PERF-PROVIDER","enabled":true,"createdAt":"2026-08-25T00:00:00Z","updatedAt":"2026-08-25T00:00:00Z"}'::jsonb, 0, now(), now())
ON CONFLICT (id) DO UPDATE SET
  payload=excluded.payload,
  version=logistics_provider.version + 1,
  updated_at=excluded.updated_at;

INSERT INTO logistics_channel(id, provider_id, code, rule_id, payload, version, created_at, updated_at)
VALUES ('22222222-2222-4222-8222-222222222222', '11111111-1111-4111-8111-111111111111', 'PERF-CHANNEL', 9001,
  '{"id":"22222222-2222-4222-8222-222222222222","providerId":"11111111-1111-4111-8111-111111111111","ruleId":9001,"name":"性能普货专线","code":"PERF-CHANNEL","type":"专线","logisticsAttribute":"普货","enabled":true,"currentVersionId":"33333333-3333-4333-8333-333333333333","createdAt":"2026-08-25T00:00:00Z","updatedAt":"2026-08-25T00:00:00Z"}'::jsonb, 0, now(), now())
ON CONFLICT (id) DO UPDATE SET
  payload=excluded.payload,
  version=logistics_channel.version + 1,
  updated_at=excluded.updated_at;

INSERT INTO logistics_version(id, channel_id, version_number, status, source_hash, payload, created_at, published_at)
VALUES ('33333333-3333-4333-8333-333333333333', '22222222-2222-4222-8222-222222222222', 1, 'published', repeat('a',64),
  jsonb_build_object(
    'id','33333333-3333-4333-8333-333333333333',
    'channelId','22222222-2222-4222-8222-222222222222',
    'versionNumber',1,
    'status','published',
    'sourceHash',repeat('a',64),
    'fileName','performance-fixture.xlsx',
    'importedAt','2026-08-25T00:00:00Z',
    'importedBy','PERFADMIN',
    'publishedAt','2026-08-25T00:00:00Z',
    'publishedBy','PERFADMIN',
    'rows',
    jsonb_build_array(
      jsonb_build_object('areaName','美国','countryCode','US','etaMinDays',6,'etaMaxDays',12,'prohibitedMarks','','allowedMarks','','maxPerimeterCm',0,'maxSideCm',0,'volumeDivisor',8000,'weightFromKg',0,'weightToKg',30,'startWeightKg',0,'pricePerKg',48,'minChargeWeightKg',0.05,'firstWeightKg',0,'firstWeightPrice',0,'nextWeightKg',0,'nextWeightPrice',0,'intervalPrice',0,'registrationFee',8,'surcharge',0,'fuelSurchargeRate',0,'prohibitGeneralCargo',false,'volumetric',true,'phoneRequired',false,'zoneName','','zoneExclude',false),
      jsonb_build_object('areaName','澳大利亚','countryCode','AU','etaMinDays',8,'etaMaxDays',15,'prohibitedMarks','','allowedMarks','','maxPerimeterCm',0,'maxSideCm',0,'volumeDivisor',8000,'weightFromKg',0,'weightToKg',30,'startWeightKg',0,'pricePerKg',55,'minChargeWeightKg',0.05,'firstWeightKg',0,'firstWeightPrice',0,'nextWeightKg',0,'nextWeightPrice',0,'intervalPrice',0,'registrationFee',10,'surcharge',0,'fuelSurchargeRate',0,'prohibitGeneralCargo',false,'volumetric',true,'phoneRequired',false,'zoneName','澳大利亚1区','zoneExclude',false),
      jsonb_build_object('areaName','澳大利亚','countryCode','AU','etaMinDays',8,'etaMaxDays',15,'prohibitedMarks','','allowedMarks','','maxPerimeterCm',0,'maxSideCm',0,'volumeDivisor',8000,'weightFromKg',0,'weightToKg',30,'startWeightKg',0,'pricePerKg',58,'minChargeWeightKg',0.05,'firstWeightKg',0,'firstWeightPrice',0,'nextWeightKg',0,'nextWeightPrice',0,'intervalPrice',0,'registrationFee',10,'surcharge',0,'fuelSurchargeRate',0,'prohibitGeneralCargo',false,'volumetric',true,'phoneRequired',false,'zoneName','澳大利亚2区','zoneExclude',false),
      jsonb_build_object('areaName','澳大利亚','countryCode','AU','etaMinDays',8,'etaMaxDays',15,'prohibitedMarks','','allowedMarks','','maxPerimeterCm',0,'maxSideCm',0,'volumeDivisor',8000,'weightFromKg',0,'weightToKg',30,'startWeightKg',0,'pricePerKg',61,'minChargeWeightKg',0.05,'firstWeightKg',0,'firstWeightPrice',0,'nextWeightKg',0,'nextWeightPrice',0,'intervalPrice',0,'registrationFee',10,'surcharge',0,'fuelSurchargeRate',0,'prohibitGeneralCargo',false,'volumetric',true,'phoneRequired',false,'zoneName','澳大利亚3区','zoneExclude',false),
      jsonb_build_object('areaName','澳大利亚','countryCode','AU','etaMinDays',8,'etaMaxDays',15,'prohibitedMarks','','allowedMarks','','maxPerimeterCm',0,'maxSideCm',0,'volumeDivisor',8000,'weightFromKg',0,'weightToKg',30,'startWeightKg',0,'pricePerKg',64,'minChargeWeightKg',0.05,'firstWeightKg',0,'firstWeightPrice',0,'nextWeightKg',0,'nextWeightPrice',0,'intervalPrice',0,'registrationFee',10,'surcharge',0,'fuelSurchargeRate',0,'prohibitGeneralCargo',false,'volumetric',true,'phoneRequired',false,'zoneName','澳大利亚4区','zoneExclude',false)
    ), 'issues','[]'::jsonb, 'diffRows','[]'::jsonb
  ), now(), now())
ON CONFLICT (id) DO UPDATE SET
  status=excluded.status,
  payload=excluded.payload,
  published_at=excluded.published_at;

UPDATE logistics_channel SET current_version_id='33333333-3333-4333-8333-333333333333' WHERE id='22222222-2222-4222-8222-222222222222';

INSERT INTO finance_setting(setting_key, payload, version, updated_at) VALUES
('country-classification','[{"country":"美国","code":"US","stage":"common","continent":"北美洲","sortOrder":10,"enabled":true},{"country":"澳大利亚","code":"AU","stage":"common","continent":"大洋洲","sortOrder":20,"enabled":true}]',0,now()),
('channel-policies','[{"id":"普货","category":"普货","enabled":true,"updatedAt":"2026-08-25","countryRules":[{"country":"美国","allowedChannels":["9001::燕文::PERF-CHANNEL"],"stage":"common","continent":"北美洲","sortOrder":10},{"country":"澳大利亚","allowedChannels":["9001::燕文::PERF-CHANNEL"],"stage":"common","continent":"大洋洲","sortOrder":20}]}]',0,now()),
('customer-grades','[{"grade":"S","coefficient":1.12,"enabled":true},{"grade":"A","coefficient":1.15,"enabled":true},{"grade":"B","coefficient":1.18,"enabled":true},{"grade":"C","coefficient":1.21,"enabled":true},{"grade":"D","coefficient":1.25,"enabled":true},{"grade":"E","coefficient":1.30,"enabled":true}]',0,now()),
('exchange-rate','{"usdCny":7.0,"updatedAt":"2026-08-25"}',0,now()),
('tax-settings','{"countries":[{"country":"美国","aFixedFeeUsd":1,"bPerItemFeeUsd":0.25,"selected":true,"enabled":true,"sortOrder":10}],"providers":[{"provider":"燕文","mode":"taxable","selected":true,"channels":[]}],"updatedAt":"2026-08-25"}',0,now())
ON CONFLICT (setting_key) DO UPDATE SET payload=excluded.payload, updated_at=excluded.updated_at;

INSERT INTO quotation_record(id, quote_no, owner_account, status, payload, version, created_at, updated_at)
SELECT (
    substr(md5('perf-quote-' || value),1,8) || '-' || substr(md5('perf-quote-' || value),9,4) || '-' ||
    substr(md5('perf-quote-' || value),13,4) || '-' || substr(md5('perf-quote-' || value),17,4) || '-' ||
    substr(md5('perf-quote-' || value),21,12)
  )::uuid,
  'PERFQ' || lpad(value::text, 8, '0'),
  'PERF' || lpad((((value - 1) % 30) + 1)::text, 2, '0'),
  CASE WHEN value % 5 = 0 THEN 'won' WHEN value % 7 = 0 THEN 'lost' ELSE 'pending' END,
  jsonb_build_object('id','perf-' || value,'no','PERFQ' || lpad(value::text,8,'0'),'customerName','性能客户' || value,'quoteMode',CASE WHEN value % 3=0 THEN 'bundle' ELSE 'single' END,'primarySku','PERF-SKU-' || lpad((((value - 1) % 10000) + 1)::text,5,'0'),'productCategory','服装','logisticsAttribute','普货','customerGrade','A级客户','taxCustomerType','A','monthlySalesEstimate','100','status',CASE WHEN value % 5 = 0 THEN 'won' WHEN value % 7 = 0 THEN 'lost' ELSE 'pending' END,'createdAt',(now() - make_interval(secs => value))::text,'updatedAt',(now() - make_interval(secs => value))::text,'quoteOptions',jsonb_build_array(jsonb_build_object('country','美国','channel','性能普货专线')),'revisions','[]'::jsonb),
  0, now() - make_interval(secs => value), now() - make_interval(secs => value)
FROM generate_series(1, 2000) AS value
ON CONFLICT (quote_no) DO NOTHING;

ANALYZE purchase_product;
ANALYZE quotation_record;
