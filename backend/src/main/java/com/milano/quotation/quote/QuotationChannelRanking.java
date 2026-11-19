package com.milano.quotation.quote;

import com.milano.quotation.common.AppException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.Semaphore;

/** A read-only aggregate. Never uses pricing calculators or saves quotation/review state. */
@Service
public class QuotationChannelRanking {
    private static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    private final NamedParameterJdbcTemplate jdbc;
    private final String sql,countries;
    private final Map<String,String> countryCodes=new HashMap<>(),countryLabels=new HashMap<>();
    private final Semaphore queries=new Semaphore(2);
    public record Filters(LocalDate startDate,LocalDate endDate,String country,String salesperson,String category,String keyword) {}
    public record Row(String key,String country,String provider,String channel,long uses,double share,
                      String latestAt,String channelId,String datasetId,String versionId,String state) {}
    public record Result(List<Row> items,long totalUses,int totalGroups,int pendingGroups,int unmatchedGroups,
                         double topFiveShare,int total,int page,int size,int totalPages,String generatedAt,
                         String startDate,String endDate) {}
    public QuotationChannelRanking(DataSource dataSource,ObjectMapper mapper) throws java.io.IOException {
        var template=new JdbcTemplate(dataSource);
        template.setQueryTimeout(6);
        jdbc=new NamedParameterJdbcTemplate(template);
        sql=new ClassPathResource("analytics/channel-ranking.sql").getContentAsString(StandardCharsets.UTF_8);
        for(String code:Locale.getISOCountries()) {
            String label=new Locale.Builder().setRegion(code).build().getDisplayCountry(Locale.SIMPLIFIED_CHINESE);
            countryCodes.put(code,code);countryCodes.put(label,code);countryLabels.put(code,label);
        }
        countryCodes.put("UK","GB");countryCodes.put("英国","GB");countryLabels.put("GB","英国");
        countryCodes.put("美国","US");countryCodes.put("俄罗斯","RU");
        countries=mapper.writeValueAsString(countryCodes);
    }
    static String text(String value) {
        String result=value==null?"":value.trim();
        if(result.length()>200)throw AppException.unprocessable("排行榜筛选内容过长");
        return result;
    }
    @Transactional(readOnly=true,timeout=8)
    public Result read(Filters filters,int page,int size,boolean pendingOnly) {
        LocalDate today=LocalDate.now(ZONE);
        LocalDate end=filters.endDate()==null?today:filters.endDate();
        LocalDate start=filters.startDate()==null?end.minusDays(29):filters.startDate();
        if(start.isAfter(end))throw AppException.unprocessable("开始日期不能晚于结束日期");
        if(start.isBefore(LocalDate.of(2000,1,1))||end.isAfter(today.plusDays(1)))throw AppException.unprocessable("请选择有效的报价日期");
        if(size!=5&&size!=20&&size!=50)throw AppException.unprocessable("排行榜每页支持 5、20 或 50 条");
        if(page<0)throw AppException.unprocessable("页码不能为负数");
        var params=new HashMap<String,Object>();
        params.put("start",java.sql.Timestamp.from(start.atStartOfDay(ZONE).toInstant()));
        params.put("end",java.sql.Timestamp.from(end.plusDays(1).atStartOfDay(ZONE).toInstant()));
        String country=text(filters.country());
        params.put("country",countryCodes.getOrDefault(countryCodes.containsKey(country)?country:country.toUpperCase(Locale.ROOT),country));
        params.put("salesperson",text(filters.salesperson()));params.put("category",text(filters.category()));
        params.put("keyword",text(filters.keyword()).toLowerCase(Locale.ROOT));params.put("countries",countries);
        if(!queries.tryAcquire())throw new AppException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"RANKING_BUSY","排行榜正在统计，请稍后重试");
        List<Row> rows;
        try {
            // JSON expansion estimates can trigger seconds of JIT compilation for small dashboards.
            // Transaction-local only: no global database setting or business row is changed.
            jdbc.getJdbcTemplate().queryForObject("SELECT set_config('jit','off',true)",String.class);
            rows=jdbc.query(sql,params,(rs,n)->{
                String current=rs.getString("current_id");
                String state=rs.getString("resolved_id")==null?"unmatched":current==null?"historical":rs.getBoolean("pending")?"pending-version":"unverified";
                return new Row(rs.getString("country_key")+"|"+rs.getString("identity"),
                    countryLabels.getOrDefault(rs.getString("country_key"),rs.getString("country_name")),
                    rs.getString("provider_name"),rs.getString("channel_name"),rs.getLong("uses"),0,
                    rs.getTimestamp("latest").toInstant().toString(),current,rs.getString("dataset_id"),rs.getString("version_id"),state);
            });
        } finally {queries.release();}
        long totalUses=rows.stream().mapToLong(Row::uses).sum();
        int pending=(int)rows.stream().filter(r->r.state().equals("pending-version")).count();
        int unmatched=(int)rows.stream().filter(r->r.state().equals("unmatched")).count();
        double top=percent(rows.stream().limit(5).mapToLong(Row::uses).sum(),totalUses);
        var matching=rows.stream().filter(r->!pendingOnly||r.state().equals("pending-version")).toList();
        int pages=(int)Math.ceil((double)matching.size()/size),safePage=Math.min(page,Math.max(0,pages-1));
        var items=matching.stream().skip((long)safePage*size).limit(size).map(r->new Row(r.key(),r.country(),r.provider(),
            r.channel(),r.uses(),percent(r.uses(),totalUses),r.latestAt(),r.channelId(),r.datasetId(),r.versionId(),r.state())).toList();
        return new Result(items,totalUses,rows.size(),pending,unmatched,top,matching.size(),safePage,size,pages,
            Instant.now().toString(),start.toString(),end.toString());
    }
    private static double percent(long count,long total){return total==0?0:Math.round(count*1000.0/total)/10.0;}
}
