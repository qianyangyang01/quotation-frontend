package com.milano.quotation.quote;

import com.milano.quotation.common.AppException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;

@Service
public class QuotationRecordQuery {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;
    @org.springframework.beans.factory.annotation.Autowired private QuotationCountryIndex countryIndex;
    public QuotationRecordQuery(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc=jdbc; this.mapper=mapper; }
    public record Filters(String q, String status, String country, String category, LocalDate startDate, LocalDate endDate, String lifecycle, String reviewer, String reviewStatus, boolean reviewMine) {
        public Filters(String q,String status,String country,String category,LocalDate startDate,LocalDate endDate,String lifecycle,String reviewer) { this(q,status,country,category,startDate,endDate,lifecycle,reviewer,null,false); }
        public Filters(String q,String status,String country,String category,LocalDate startDate,LocalDate endDate) { this(q,status,country,category,startDate,endDate,"active",null); }
        public Filters(String q,String status,String country,String category,LocalDate startDate,LocalDate endDate,String lifecycle) { this(q,status,country,category,startDate,endDate,lifecycle,null); }
    }
    public record Summary(long pending, long won, long lost, long total, long processed) {}
    public record Result(List<JsonNode> items, int page, int size, long total, int totalPages, Summary summary, List<String> countries) {}
    @Transactional(readOnly=true, isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Result search(String owner, Filters filters, int page, int size) {
        if(filters.startDate()!=null && filters.endDate()!=null && filters.startDate().isAfter(filters.endDate())) throw AppException.unprocessable("开始日期不能晚于结束日期");
        if(filters.status()!=null && !filters.status().isBlank() && !Set.of("pending","won","lost","processed","finance-pending","finance-approved","finance-rejected","finance-reviewing","finance-mine").contains(filters.status())) throw AppException.unprocessable("报价状态不合法");
        var reviewStatus="coalesce((select r.status from quotation_review r where r.id=quotation_record.id),nullif(payload->>'financeReviewStatus',''),'pending')";
        if(filters.reviewStatus()!=null && !filters.reviewStatus().isBlank() && !Set.of("pending","reviewing","approved","rejected").contains(filters.reviewStatus())) throw AppException.unprocessable("审核状态不合法");
        var lifecycle=filters.lifecycle()==null ? "active" : filters.lifecycle();
        if(!Set.of("active","archived","trashed").contains(lifecycle)) throw AppException.unprocessable("记录分类不合法");
        var params=new HashMap<String,Object>(); params.put("lifecycle",lifecycle);
        var countrySnapshot=countryIndex==null?null:countryIndex.snapshot(owner,lifecycle);
        var confirmed="(coalesce(payload->>'quoteConfirmed','false')='true')";
        if(countrySnapshot!=null){
            params.put("confirmedIds","{"+String.join(",",countrySnapshot.confirmedIds().stream().map(UUID::toString).toList())+"}");
            confirmed="(id=ANY(CAST(:confirmedIds AS uuid[])))";
        }
        var where=new StringBuilder(" where lifecycle_state=:lifecycle");
        if(owner!=null) { where.append(" and owner_account=:owner");params.put("owner",owner); }
        var zone=ZoneId.of("Asia/Shanghai");
        if(filters.startDate()!=null) { where.append(" and created_at>=:start");params.put("start",java.sql.Timestamp.from(filters.startDate().atStartOfDay(zone).toInstant())); }
        if(filters.endDate()!=null) { where.append(" and created_at<:end");params.put("end",java.sql.Timestamp.from(filters.endDate().plusDays(1).atStartOfDay(zone).toInstant())); }
        if(filters.status()!=null && !filters.status().isBlank()) { if (filters.status().equals("finance-pending")) {
                // Match the displayed default for legacy records without a review status.
                where.append(" and "+reviewStatus+" not in ('approved','rejected','reviewing')");
            } else if (filters.status().equals("finance-mine")) {
                where.append(" and exists(select 1 from quotation_review r where r.id=quotation_record.id and r.status='reviewing' and r.claimant_account=:reviewer)");
                params.put("reviewer",filters.reviewer()==null?"":filters.reviewer());
            } else if (filters.status().startsWith("finance-")) { where.append(" and "+reviewStatus+"=:reviewStatus");params.put("reviewStatus",filters.status().substring(8)); }
            else if (filters.status().equals("processed")) where.append(" and status in ('pending','lost') and "+confirmed);
            else if (filters.status().equals("pending")) where.append(" and status in ('pending','lost') and not "+confirmed);
            else { where.append(" and status=:status");params.put("status",filters.status()); } }
        if(filters.reviewStatus()!=null && !filters.reviewStatus().isBlank()) {
            if(filters.reviewStatus().equals("pending")) where.append(" and "+reviewStatus+" not in ('approved','rejected','reviewing')");
            else { where.append(" and "+reviewStatus+"=:independentReviewStatus");params.put("independentReviewStatus",filters.reviewStatus()); }
        }
        if(filters.reviewMine()) {
            where.append(" and exists(select 1 from quotation_review r where r.id=quotation_record.id and r.status='reviewing' and r.claimant_account=:reviewer)");
            params.put("reviewer",filters.reviewer()==null?"":filters.reviewer());
        }
        if(filters.category()!=null && !filters.category().isBlank()) { where.append(" and payload->>'productCategory'=:category");params.put("category",filters.category()); }
        var options="jsonb_array_elements(case when jsonb_typeof(payload->'quoteOptions')='array' then payload->'quoteOptions' else '[]'::jsonb end)";
        if(filters.country()!=null && !filters.country().isBlank()) { where.append(" and (payload->>'country'=:country or exists(select 1 from "+options+" o where o->>'country'=:country))");params.put("country",filters.country()); }
        if(filters.q()!=null && !filters.q().isBlank()) {
            where.append(" and position(:q in lower(concat_ws(' ',quote_no,payload->>'customerName',payload->>'primarySku',payload->>'productCategory',payload->>'country',payload->>'carrier',payload->>'channel',payload->>'salespersonName',(select string_agg(concat_ws(' ',o->>'country',o->>'carrier',o->>'channel',o->>'rule'),' ') from "+options+" o))))>0");
            params.put("q",filters.q().trim().toLowerCase(Locale.ROOT));
        }
        var summary=jdbc.queryForObject("select count(*) total,count(*) filter(where status in ('pending','lost') and not "+confirmed+") pending,count(*) filter(where status in ('pending','lost') and "+confirmed+") processed,count(*) filter(where status='won') won,count(*) filter(where status='lost') lost from quotation_record"+where,params,(rs,n)->new Summary(rs.getLong("pending"),rs.getLong("won"),rs.getLong("lost"),rs.getLong("total"),rs.getLong("processed")));
        int safeSize=Math.max(1,Math.min(100,size)); int pages=(int)Math.ceil((double)summary.total()/safeSize); int safePage=Math.max(0,Math.min(page,Math.max(0,pages-1)));
        params.put("limit",safeSize);params.put("offset",(long)safePage*safeSize);
        var items=jdbc.query("select id,payload,version,lifecycle_state from quotation_record"+where+" order by created_at desc,id desc limit :limit offset :offset",params,(rs,n)->{var payload=(tools.jackson.databind.node.ObjectNode)mapper.readTree(rs.getString("payload"));payload.put("id",rs.getObject("id",UUID.class).toString());payload.put("_version",rs.getLong("version"));payload.put("lifecycleState",rs.getString("lifecycle_state"));return (JsonNode)payload;});
        if(!items.isEmpty()) {
            var reviewRows=jdbc.query("select id,state,version from quotation_review where id in (:ids)",Map.of("ids",items.stream().map(p->UUID.fromString(p.path("id").asText())).toList()),
                (rs,n)->Map.entry(rs.getObject("id",UUID.class),Map.entry(mapper.readTree(rs.getString("state")),rs.getLong("version"))));
            var states=new HashMap<UUID,Map.Entry<JsonNode,Long>>();reviewRows.forEach(e->states.put(e.getKey(),e.getValue()));
            for(var item:items) {
                var state=states.get(UUID.fromString(item.path("id").asText()));
                QuotationReviewService.overlay((tools.jackson.databind.node.ObjectNode)item,state==null?QuotationReviewService.legacy(item):state.getKey(),state==null?0:state.getValue());
            }
        }
        var ownerWhere=" where lifecycle_state=:lifecycle"+(owner==null?"":" and owner_account=:owner");
        var countries=countrySnapshot!=null?countrySnapshot.countries():jdbc.queryForList("select distinct country from (select payload->>'country' country from quotation_record"+ownerWhere+" union select o->>'country' country from quotation_record cross join lateral "+options+" o"+ownerWhere+") c where country is not null and country<>'' and country<>'—' order by country",params,String.class);
        return new Result(items,safePage,safeSize,summary.total(),pages,summary,countries);
    }
}
