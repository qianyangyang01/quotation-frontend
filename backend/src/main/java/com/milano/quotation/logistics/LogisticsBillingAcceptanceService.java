package com.milano.quotation.logistics;

import com.milano.quotation.common.AppException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import java.util.*;

@Service
public class LogisticsBillingAcceptanceService {
    private final JdbcClient jdbc;private final ObjectMapper mapper;private final LogisticsDatasetGuard guard;private final LogisticsBillingEngine engine;
    public LogisticsBillingAcceptanceService(JdbcClient jdbc,ObjectMapper mapper,LogisticsDatasetGuard guard,LogisticsBillingEngine engine){this.jdbc=jdbc;this.mapper=mapper;this.guard=guard;this.engine=engine;}
    public ObjectNode status(UUID version){
        var v=version(version);var out=mapper.createObjectNode().put("versionId",version.toString()).put("engineVersion",LogisticsBillingEngine.VERSION)
                .put("fingerprint",v.path("fingerprint").asText()).put("pricePublished",v.path("status").asText().equals("published"));
        out.set("unsupportedReasons",mapper.valueToTree(engine.unsupported(v.path("payload").path("rows"))));
        out.put("quoteReady",jdbc.sql("select logistics_version_quote_ready(:id)").param("id",version).query(Boolean.class).single());
        out.set("records",mapper.valueToTree(jdbc.sql("select to_jsonb(a)::text from logistics_billing_acceptance a where version_id=:id order by reviewed_at desc,id")
                .param("id",version).query((rs,n)->mapper.readTree(rs.getString(1))).list()));return out;
    }
    @Transactional public ObjectNode approve(UUID version,ObjectNode input,String actor){
        var before=version(version);guard.channel(UUID.fromString(before.path("channelId").asText()));var v=version(version);
        if(!v.path("status").asText().equals("published")||!v.path("currentVersionId").asText().equals(version.toString()))throw AppException.conflict("仅能验收当前已发布价格版本");
        if(!input.path("fingerprint").asText().equals(v.path("fingerprint").asText())||!input.path("engineVersion").asText().equals(LogisticsBillingEngine.VERSION))throw AppException.conflict("价格或计费实现已变化，请重新核验");
        if(!input.path("reviewConfirmed").asBoolean()||input.path("note").asText().isBlank()||input.path("sourceReference").asText().isBlank())throw AppException.unprocessable("请确认原表规则并填写核对依据和审核备注");
        var rows=v.path("payload").path("rows");var reasons=engine.unsupported(rows);if(!reasons.isEmpty())throw AppException.unprocessable("不能验收未适配规则："+String.join("；",reasons));
        var samples=input.path("samples");if(!samples.isArray()||samples.size()<2||samples.size()>20000)throw AppException.unprocessable("需提交独立核算的运费样本，最多20000条");
        var covered=new HashMap<String,Set<String>>();var evidence=mapper.createArrayNode();int rejected=0;
        for(var sample:samples){
            if(sample.path("sourceReference").asText().isBlank())throw AppException.unprocessable("每条样本需原表位置和人工核算依据");
            ObjectNode result=null;try{result=engine.calculate(rows,sample.path("input"));}catch(AppException e){if(!sample.path("expectRejected").asBoolean())throw e;rejected++;evidence.addObject().set("sample",sample);continue;}
            if(sample.path("expectRejected").asBoolean())throw AppException.unprocessable("应阻断的样本却可报价");
            var expected=LogisticsBillingEngine.n(sample,"expectedTotal");if(!sample.has("expectedTotal")||expected.compareTo(result.path("total").decimalValue())!=0)throw AppException.unprocessable("人工预期运费与系统结果不一致");
            var matchedRow=rows.get(result.path("rowIndex").asInt());
            covered.computeIfAbsent(LogisticsBillingEngine.acceptanceTierKey(matchedRow),k->new HashSet<>()).add(LogisticsBillingEngine.n(sample.path("input"),"weightKg").stripTrailingZeros().toPlainString());
            evidence.addObject().set("sample",sample);((ObjectNode)evidence.get(evidence.size()-1)).set("result",result);
        }
        var tiers=new LinkedHashMap<String,Integer>();for(int i=0;i<rows.size();i++)if(LogisticsBillingEngine.available(rows.get(i)))tiers.putIfAbsent(LogisticsBillingEngine.acceptanceTierKey(rows.get(i)),i);
        for(var tier:tiers.entrySet())if(covered.getOrDefault(tier.getKey(),Set.of()).size()<2)throw AppException.unprocessable("每个等价价格档至少需要两个不同重量的独立核算样本，第"+(tier.getValue()+1)+"档未覆盖");
        if(rejected<1)throw AppException.unprocessable("至少需要一个越界或准入拒绝样本");
        var record=mapper.createObjectNode().put("note",input.path("note").asText()).put("sourceReference",input.path("sourceReference").asText());record.set("evidence",evidence);
        jdbc.sql("insert into logistics_billing_acceptance(id,version_id,rows_fingerprint,engine_version,kind,payload,reviewed_by) values(:id,:version,:hash,:engine,'verified',cast(:payload as jsonb),:actor)")
                .param("id",UUID.randomUUID()).param("version",version).param("hash",v.path("fingerprint").asText()).param("engine",LogisticsBillingEngine.VERSION).param("payload",record.toString()).param("actor",actor).update();
        return status(version);
    }
    ObjectNode version(UUID id){return jdbc.sql("select jsonb_build_object('id',v.id,'channelId',c.id,'currentVersionId',c.current_version_id,'status',v.status,'payload',v.payload,'fingerprint',md5(coalesce(v.payload->'rows','[]'::jsonb)::text))::text from logistics_version v join logistics_channel c on c.id=v.channel_id where v.id=:id")
            .param("id",id).query((rs,n)->(ObjectNode)mapper.readTree(rs.getString(1))).optional().orElseThrow(()->AppException.notFound("物流版本不存在"));}
}
