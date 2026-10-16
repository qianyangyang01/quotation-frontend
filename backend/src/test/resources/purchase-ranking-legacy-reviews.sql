        with ordinary as (
          select q.id::text as id,q.payload,
            coalesce(r.state,q.payload) as state
          from quotation_record q left join quotation_review r on r.id=q.id
        ), ordinary_events as (
          select id,payload,e from ordinary cross join lateral jsonb_array_elements(
            case when jsonb_array_length(coalesce(state->'history','[]'::jsonb))>0 then state->'history'
            when state->>'financeReviewStatus' in ('approved','rejected','channel-exempt','logistics-exempt') then
              jsonb_build_array(jsonb_build_object('action','legacy-review','after',state->'financeReviewStatus','at',state->'financeReviewedAt'))
            else '[]'::jsonb end) e
          where e->>'action' in ('complete','legacy-review','fob-return','fob-approve')
        )
        select 'quote:'||id as id,
          (case when payload->>'quoteMode' in ('freight-trial','shipping-only') then '[]'::jsonb
           when payload->>'quoteMode'='bundle' then coalesce((select jsonb_agg(jsonb_build_object('sku',b->'sku','source','standard')) from jsonb_array_elements(payload->'bundleItems') b),'[]'::jsonb)
           when payload->>'quoteMode'='fob' and payload#>>'{fob,schemaVersion}'='2' then coalesce((select jsonb_agg(jsonb_build_object('sku',b#>'{product,sku}','source',b#>'{product,source}')) from jsonb_array_elements(payload#>'{fob,items}') b),'[]'::jsonb)
           when payload->>'quoteMode'='fob' then jsonb_build_array(jsonb_build_object('sku',payload#>'{fob,product,sku}','source',payload#>'{fob,product,source}'))
           else jsonb_build_array(jsonb_build_object('sku',payload->'primarySku','source','standard')) end)::text as refs,
          jsonb_build_object('action',e->'action','at',e->'at','after',e->'after',
            'priceException',e->'priceException','itemChanges',e->'itemChanges')::text as event
        from ordinary_events
        union all
        select 'batch:'||b.id::text,
          coalesce((select jsonb_agg(jsonb_build_object('sku',i#>'{input,primarySku}','source','standard')) from jsonb_array_elements(b.payload->'items') i),'[]'::jsonb)::text,
          jsonb_build_object('action','batch-complete','at',e->'at','itemChanges',
            (select jsonb_agg(jsonb_build_object('index',i->'index','sku',i->'sku','status',i#>'{after,status}',
              'priceException',i#>'{after,priceException}')) from jsonb_array_elements(e->'items') i))::text
        from batch_single_quotation b join batch_single_review r on r.record_id=b.id
          cross join lateral jsonb_array_elements(coalesce(r.state->'history','[]'::jsonb)) e
        where e->>'action'='complete'
