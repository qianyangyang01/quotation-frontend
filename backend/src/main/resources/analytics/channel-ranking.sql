WITH catalog AS MATERIALIZED (
 SELECT c.id::text id,c.current_version_id::text version_id,c.dataset_id::text dataset_id,
   b.company_channel_id::text company_id,c.code,
   trim(p.payload->>'name') provider,trim(c.payload->>'name') channel,d.status dataset_status,
   c.payload->>'channelKey' channel_key,c.archived_at,
   EXISTS(SELECT 1 FROM logistics_version v WHERE v.channel_id=c.id AND v.status='draft') pending
 FROM logistics_channel c JOIN logistics_provider p ON p.id=c.provider_id
 JOIN logistics_dataset d ON d.id=c.dataset_id
 LEFT JOIN logistics_company_binding b ON b.channel_id=c.id
), live_catalog AS MATERIALIZED (
 SELECT DISTINCT ON (coalesce('company:'||company_id,'id:'||id)) *,
   coalesce('company:'||company_id,'id:'||id) identity
 FROM catalog WHERE dataset_status='active' AND archived_at IS NULL
 ORDER BY coalesce('company:'||company_id,'id:'||id),pending DESC,id
), names AS MATERIALIZED (
 SELECT *,count(*) OVER(PARTITION BY provider,channel) matches
 FROM catalog WHERE dataset_status='active' AND archived_at IS NULL
), codes AS MATERIALIZED (
 SELECT *,count(*) OVER(PARTITION BY provider,code) matches
 FROM catalog WHERE dataset_status='active' AND archived_at IS NULL
), sources AS (
 SELECT q.id,q.created_at,raw.*,
   CASE WHEN :category='' AND :keyword='' THEN '' WHEN raw."quoteMode"='shipping-only' THEN '仅代发货报价'
    ELSE (SELECT CASE WHEN count(DISTINCT coalesce(nullif(trim(p.payload->>'category'),''),'其他'))=1
          THEN min(coalesce(nullif(trim(p.payload->>'category'),''),'其他')) ELSE '其他' END
      FROM regexp_split_to_table(upper(coalesce(raw."primarySku",'')), '[、,+\s]+') sku_value
      LEFT JOIN purchase_product p ON p.sku=sku_value
      WHERE sku_value NOT IN ('','—','-','--','暂无数据','无','N/A','NA')) END category
 FROM quotation_record q
 CROSS JOIN LATERAL jsonb_to_record(q.payload) raw("quoteMode" text,"isTest" text,"primarySku" text,
  "salespersonName" text,"salespersonAccount" text,"no" text,"customerName" text,"productSummary" text,
  "country" text,"carrier" text,"channel" text,"systemQuoteUsd" jsonb,"quoteOptions" jsonb,"specifiedQuotes" jsonb)
 WHERE q.lifecycle_state IN ('active','archived') AND q.created_at>=:start AND q.created_at<:end
   AND coalesce(raw."quoteMode",'single') NOT IN ('freight-trial','fob')
   AND coalesce(raw."isTest",'false')<>'true'
   AND (:salesperson='' OR raw."salespersonName"=:salesperson)
), selected AS (
 SELECT * FROM sources q WHERE (:category='' OR category=:category)
 AND (:keyword='' OR position(:keyword IN lower(concat_ws(' ',q.no,q."customerName",q."primarySku",q."productSummary",
   q."salespersonName",q."salespersonAccount",category,q.country,
   (SELECT string_agg(o->>'country',' ') FROM jsonb_array_elements(
    CASE WHEN jsonb_typeof(q."quoteOptions")='array' THEN q."quoteOptions"
     WHEN jsonb_typeof(q."specifiedQuotes")='array' THEN q."specifiedQuotes" ELSE '[]'::jsonb END) o))))>0)
), expanded AS MATERIALIZED (
 SELECT q.id,q.created_at,
  jsonb_build_object('countryCode',o->'countryCode','logisticsChannelId',o->'logisticsChannelId',
   'channelKey',o->'channelKey','channelCode',o->'channelCode') o,
  trim(coalesce(o->>'country','')) country_name,trim(coalesce(o->>'carrier','')) provider_name,
  trim(coalesce(o->>'channel','')) channel_name
 FROM selected q CROSS JOIN LATERAL jsonb_array_elements(
  CASE WHEN jsonb_typeof(q."quoteOptions")='array' THEN q."quoteOptions"
   WHEN jsonb_typeof(q."specifiedQuotes")='array' THEN q."specifiedQuotes"
   ELSE jsonb_build_array(jsonb_build_object('country',q.country,'carrier',q.carrier,
    'channel',q.channel,'quote1Usd',q."systemQuoteUsd")) END) o
 WHERE o->>'available' IS DISTINCT FROM 'false'
 AND EXISTS(SELECT 1 FROM (VALUES (o->>'quote1Usd'),(o->>'quote2Usd'),(o->>'quote3Usd'),(o->>'quoteCustomUsd'),(o->>'quoteCny')) price(value)
   WHERE CASE WHEN price.value ~ '^[0-9]+([.][0-9]+)?$' THEN price.value::numeric>0 ELSE false END)
), resolved AS (
 SELECT e.*,
  coalesce(CAST(:countries AS jsonb)->>upper(country_name),CAST(:countries AS jsonb)->>country_name,
    nullif(upper(trim(o->>'countryCode')),''),country_name) country_key,
  coalesce(by_id.id,by_code.id,by_name.id) resolved_id,
  coalesce(by_id.company_id,by_code.company_id,by_name.company_id) company_id
 FROM expanded e
 LEFT JOIN catalog by_id ON by_id.id=nullif(e.o->>'logisticsChannelId','')
 LEFT JOIN codes by_code ON nullif(e.o->>'logisticsChannelId','') IS NULL
   AND by_code.provider=e.provider_name AND by_code.code=e.o->>'channelCode' AND by_code.matches=1
 LEFT JOIN names by_name ON nullif(e.o->>'logisticsChannelId','') IS NULL AND by_code.id IS NULL
   AND nullif(e.o->>'channelCode','') IS NULL
   AND by_name.provider=e.provider_name AND by_name.channel=e.channel_name AND by_name.matches=1
 WHERE country_name NOT IN ('','—','-') AND provider_name NOT IN ('','—','-') AND channel_name NOT IN ('','—','-')
), keyed AS (
 SELECT *,CASE WHEN company_id IS NOT NULL THEN 'company:'||company_id
    WHEN resolved_id IS NOT NULL THEN 'id:'||resolved_id
    ELSE 'unmapped:'||jsonb_build_array(provider_name,channel_name,nullif(o->>'logisticsChannelId',''),
       nullif(o->>'channelKey',''),nullif(o->>'channelCode',''))::text END identity
 FROM resolved WHERE :country='' OR country_key=:country
), usages AS (
 SELECT DISTINCT ON (id,country_key,identity) id,country_key,identity,country_name,provider_name,channel_name,
   resolved_id,company_id,created_at
 FROM keyed ORDER BY id,country_key,identity,provider_name,channel_name
), grouped AS (
 SELECT country_key,identity,count(*) uses,max(created_at) latest,
   (array_agg(country_name ORDER BY created_at DESC,id))[1] country_name,
   (array_agg(provider_name ORDER BY created_at DESC,id))[1] provider_name,
   (array_agg(channel_name ORDER BY created_at DESC,id))[1] channel_name,
   max(resolved_id) resolved_id,max(company_id) company_id
 FROM usages GROUP BY country_key,identity
)
SELECT g.*,current.id current_id,current.dataset_id,current.version_id,coalesce(current.pending,false) pending
FROM grouped g LEFT JOIN live_catalog current ON current.identity=g.identity
ORDER BY uses DESC,latest DESC,country_key,identity
