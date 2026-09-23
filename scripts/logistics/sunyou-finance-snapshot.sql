\set ON_ERROR_STOP on
begin isolation level repeatable read read only;
select jsonb_build_object(
 'datasetId', logistics_active_dataset(),
 'settings',(select jsonb_object_agg(setting_key,jsonb_build_object('version',version,'payload',payload)) from finance_setting),
 'pricesHash',(select md5(string_agg(id::text||':'||md5(payload::text),'|' order by id)) from logistics_version),
 'channelsHash',(select md5(string_agg(id::text||':'||md5(payload::text)||':'||coalesce(current_version_id::text,''),'|' order by id)) from logistics_channel),
 'channels',(select jsonb_agg(jsonb_build_object(
   'id',c.id,'ruleId',c.rule_id,'provider',p.payload->>'name','code',c.code,
   'name',c.payload->>'name','companyId',b.company_channel_id,'company',d.payload,
   'enabled',coalesce((c.payload->>'enabled')::boolean,true),'quoteReady',logistics_version_quote_ready(v.id),
   'countries',(select jsonb_agg(x) from (select distinct r->>'countryCode' code,r->>'areaName' name from jsonb_array_elements(v.quote_rows) r) x),
   'rows',case when p.payload->>'name'='顺友' then v.quote_rows else null end
 ) order by c.rule_id) from logistics_channel c join logistics_provider p on p.id=c.provider_id
 join logistics_version v on v.id=c.current_version_id and v.status='published'
 left join logistics_company_binding b on b.channel_id=c.id
 left join logistics_company_channel d on d.id=b.company_channel_id
 where c.dataset_id=logistics_active_dataset() and c.archived_at is null),
 'activeImports',(select count(*) from logistics_import_batch where status in ('queued','processing')),
 'paused',(select paused from logistics_company_state where singleton)
);
commit;
