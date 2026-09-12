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
    public QuotationRecordQuery(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc=jdbc; this.mapper=mapper; }
    public record Filters(String q, String status, String country, String category, LocalDate startDate, LocalDate endDate) {}
    public record Summary(long pending, long won, long lost, long total, long processed) {}
    public record Result(List<JsonNode> items, int page, int size, long total, int totalPages, Summary summary, List<String> countries) {}
    @Transactional(readOnly=true, isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Result search(String owner, Filters filters, int page, int size) {
        if(filters.startDate()!=null && filters.endDate()!=null && filters.startDate().isAfter(filters.endDate())) throw AppException.unprocessable("开始日期不能晚于结束日期");
        if(filters.status()!=null && !filters.status().isBlank() && !Set.of("pending","won","lost","processed").contains(filters.status())) throw AppException.unprocessable("报价状态不合法");
        var params=new HashMap<String,Object>(); var where=new StringBuilder(" where 1=1");
        if(owner!=null) { where.append(" and owner_account=:owner");params.put("owner",owner); }
        var zone=ZoneId.of("Asia/Shanghai");
        if(filters.startDate()!=null) { where.append(" and created_at>=:start");params.put("start",java.sql.Timestamp.from(filters.startDate().atStartOfDay(zone).toInstant())); }
        if(filters.endDate()!=null) { where.append(" and created_at<:end");params.put("end",java.sql.Timestamp.from(filters.endDate().plusDays(1).atStartOfDay(zone).toInstant())); }
        if(filters.status()!=null && !filters.status().isBlank()) { if (filters.status().equals("processed")) where.append(" and status in ('pending','lost') and payload->>'quoteConfirmed'='true'");
            else if (filters.status().equals("pending")) where.append(" and status in ('pending','lost') and coalesce(payload->>'quoteConfirmed','false')<>'true'");
            else { where.append(" and status=:status");params.put("status",filters.status()); } }
        if(filters.category()!=null && !filters.category().isBlank()) { where.append(" and payload->>'productCategory'=:category");params.put("category",filters.category()); }
        var options="jsonb_array_elements(case when jsonb_typeof(payload->'quoteOptions')='array' then payload->'quoteOptions' else '[]'::jsonb end)";
        if(filters.country()!=null && !filters.country().isBlank()) { where.append(" and (payload->>'country'=:country or exists(select 1 from "+options+" o where o->>'country'=:country))");params.put("country",filters.country()); }
        if(filters.q()!=null && !filters.q().isBlank()) {
            where.append(" and position(:q in lower(concat_ws(' ',quote_no,payload->>'customerName',payload->>'primarySku',payload->>'productCategory',payload->>'country',payload->>'carrier',payload->>'channel',payload->>'salespersonName',(select string_agg(concat_ws(' ',o->>'country',o->>'carrier',o->>'channel',o->>'rule'),' ') from "+options+" o))))>0");
            params.put("q",filters.q().trim().toLowerCase(Locale.ROOT));
        }
        var summary=jdbc.queryForObject("select count(*) total,count(*) filter(where status in ('pending','lost') and coalesce(payload->>'quoteConfirmed','false')<>'true') pending,count(*) filter(where status in ('pending','lost') and payload->>'quoteConfirmed'='true') processed,count(*) filter(where status='won') won,count(*) filter(where status='lost') lost from quotation_record"+where,params,(rs,n)->new Summary(rs.getLong("pending"),rs.getLong("won"),rs.getLong("lost"),rs.getLong("total"),rs.getLong("processed")));
        int safeSize=Math.max(1,Math.min(100,size)); int pages=(int)Math.ceil((double)summary.total()/safeSize); int safePage=Math.max(0,Math.min(page,Math.max(0,pages-1)));
        params.put("limit",safeSize);params.put("offset",(long)safePage*safeSize);
        var items=jdbc.query("select payload,version from quotation_record"+where+" order by created_at desc,id desc limit :limit offset :offset",params,(rs,n)->{var payload=(tools.jackson.databind.node.ObjectNode)mapper.readTree(rs.getString("payload"));payload.put("_version",rs.getLong("version"));return (JsonNode)payload;});
        var ownerWhere=owner==null?"":" where owner_account=:owner";
        var countries=jdbc.queryForList("select distinct country from (select payload->>'country' country from quotation_record"+ownerWhere+" union select o->>'country' country from quotation_record cross join lateral "+options+" o"+ownerWhere+") c where country is not null and country<>'' and country<>'—' order by country",params,String.class);
        return new Result(items,safePage,safeSize,summary.total(),pages,summary,countries);
    }
}
