package com.milano.quotation.finance;

import com.milano.quotation.audit.AuditService;
import com.milano.quotation.common.ApiResponse;
import com.milano.quotation.common.AppException;
import com.milano.quotation.security.QuotationPrincipal;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.*;

@RestController
@RequestMapping("/api/v1/finance-settings/exchange-rate/history")
public class ExchangeRateHistory {
    private final JdbcClient jdbc;
    public ExchangeRateHistory(JdbcClient jdbc) { this.jdbc=jdbc; }
    private static final String WHERE = " resource_type='finance-setting' and resource_id='exchange-rate' and action='finance.exchange-rate.update' and outcome='success' ";
    private static final String FIELDS = "id::text, created_at, actor_account, detail->>'actorName' as actor_name, detail->>'currency' as currency, detail->>'before' as before_rate, detail->>'after' as after_rate";
    public record Entry(String id, String modifiedAt, String actorAccount, String actorName, String currency, BigDecimal before, BigDecimal after) {}
    private static final org.springframework.jdbc.core.RowMapper<Entry> MAPPER=(rs,n)->new Entry(rs.getString("id"),rs.getTimestamp("created_at").toInstant().toString(),rs.getString("actor_account"),rs.getString("actor_name"),rs.getString("currency"),rs.getBigDecimal("before_rate"),rs.getBigDecimal("after_rate"));

    @GetMapping @PreAuthorize("hasAuthority('PERM_finance')")
    @Transactional(readOnly=true, isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    ApiResponse<?> list(@RequestParam(defaultValue="all") String currency, @RequestParam(defaultValue="0") int page) {
        if (!List.of("all","usdCny","eurUsd").contains(currency) || page<0) throw AppException.unprocessable("汇率记录筛选或页码无效");
        String where=WHERE+" and (:currency='all' or detail->>'currency'=:currency)";
        long total=jdbc.sql("select count(*) from audit_log where "+where).param("currency",currency).query(Long.class).single();
        int actualPage=(int)Math.min(page,Math.max(0,(total-1)/10));
        var items=jdbc.sql("select "+FIELDS+" from audit_log where "+where+" order by created_at desc,id desc limit 10 offset :offset")
            .param("currency",currency).param("offset",(long)actualPage*10).query(MAPPER).list();
        var latest=new LinkedHashMap<String,Entry>();
        jdbc.sql("select distinct on (detail->>'currency') "+FIELDS+" from audit_log where "+WHERE+" order by detail->>'currency',created_at desc,id desc")
            .query(MAPPER).list().forEach(e->latest.put(e.currency(),e));
        return ApiResponse.ok(Map.of("items",items,"total",total,"page",actualPage,"size",10,"latest",latest));
    }

    static BigDecimal rate(JsonNode value,String currency) {
        var node=value.path(currency);
        if(currency.equals("usdCny")&&!node.isNumber())node=value.path("usdToCny");
        return node.isNumber()?node.decimalValue():null;
    }
    static boolean changed(JsonNode before,JsonNode after,String currency) {
        var a=rate(before,currency);var b=rate(after,currency);
        return a==null?b!=null:b==null||a.compareTo(b)!=0;
    }
    static void record(AuditService audit,JsonNode before,JsonNode after) {
        var auth=SecurityContextHolder.getContext().getAuthentication();
        String name=auth.getPrincipal() instanceof QuotationPrincipal p?p.displayName():auth.getName();
        for(String currency:List.of("usdCny","eurUsd"))if(changed(before,after,currency)) {
            var detail=new LinkedHashMap<String,Object>();
            detail.put("currency",currency);detail.put("before",rate(before,currency));detail.put("after",rate(after,currency));detail.put("actorName",name);
            audit.record("finance.exchange-rate.update","finance-setting","exchange-rate","success",detail);
        }
    }
}
