package com.milano.quotation.quote;

import com.milano.quotation.common.AppException;
import com.milano.quotation.security.UserAccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

@Service
public class QuotationMigrationService {
    private final QuotationTemplateRepository templates;private final QuotationRecordRepository records;private final UserAccountRepository users;
    public QuotationMigrationService(QuotationTemplateRepository templates,QuotationRecordRepository records,UserAccountRepository users){this.templates=templates;this.records=records;this.users=users;}

    @Transactional public ObjectNode applyTemplates(JsonNode values,JsonNode mappings,String actor,String sourceHash){var created=JsonNodeFactory.instance.arrayNode();if(!values.isArray())throw AppException.unprocessable("报价模板迁移数据必须是数组");int index=0;for(var item:values){var owner=owner(item,mappings,actor,true);var id=stable(sourceHash,"template",item.path("id").asText(String.valueOf(index++)));var name=item.path("name").asText("").trim();if(name.isEmpty()||name.length()>120)throw AppException.unprocessable("历史报价模板名称不合法");var payload=(ObjectNode)item.deepCopy();payload.put("legacyMigrationSource",sourceHash);var existing=templates.findById(id);if(existing.isPresent()){if(!existing.get().payload.equals(payload)||!existing.get().ownerAccount.equalsIgnoreCase(owner)||!existing.get().name.equals(name))throw AppException.conflict("报价模板ID冲突："+id);continue;}var now=parseTime(item.path("createdAt").asText(),Instant.now());var row=new QuotationTemplateEntity();row.id=id;row.ownerAccount=owner;row.name=name;row.payload=payload;row.createdAt=now;row.updatedAt=parseTime(item.path("updatedAt").asText(),now);templates.save(row);created.add(id.toString());}return JsonNodeFactory.instance.objectNode().set("createdTemplates",created);}
    @Transactional public ObjectNode applyRecords(JsonNode values,JsonNode mappings,String actor,String sourceHash){var created=JsonNodeFactory.instance.arrayNode();if(!values.isArray())throw AppException.unprocessable("历史报价迁移数据必须是数组");int index=0;for(var item:values){var owner=owner(item,mappings,actor,false);var legacyId=item.path("id").asText(String.valueOf(index++));var id=stable(sourceHash,"quotation",legacyId);var quoteNo=item.path("no").asText(item.path("quoteNo").asText("")).trim();if(quoteNo.isEmpty())quoteNo="MIG"+id.toString().replace("-","").substring(0,20).toUpperCase(Locale.ROOT);if(quoteNo.length()>40)throw AppException.unprocessable("历史报价编号过长");var status=item.path("status").asText("pending");if(status.length()>16)status="pending";var payload=(ObjectNode)item.deepCopy();payload.put("id",id.toString());payload.put("no",quoteNo);payload.put("salespersonAccount",owner);payload.put("legacyMigrationSource",sourceHash);var existing=records.findById(id);if(existing.isPresent()){if(!existing.get().payload.equals(payload)||!existing.get().ownerAccount.equalsIgnoreCase(owner)||!existing.get().quoteNo.equals(quoteNo)||!existing.get().status.equals(status))throw AppException.conflict("历史报价ID冲突："+id);continue;}if(records.findByQuoteNo(quoteNo).isPresent())throw AppException.conflict("历史报价编号冲突："+quoteNo);var now=parseTime(item.path("createdAt").asText(),Instant.now());var row=new QuotationRecordEntity();row.id=id;row.quoteNo=quoteNo;row.ownerAccount=owner;row.status=status;row.payload=payload;row.createdAt=now;row.updatedAt=parseTime(item.path("updatedAt").asText(),now);records.save(row);created.add(id.toString());}return JsonNodeFactory.instance.objectNode().set("createdQuotations",created);}
    @Transactional public ObjectNode rollback(JsonNode execution){int templateCount=0,recordCount=0;for(var value:execution.path("createdQuotations")){try{var id=UUID.fromString(value.asText());if(records.existsById(id)){records.deleteById(id);recordCount++;}}catch(IllegalArgumentException ignored){}}for(var value:execution.path("createdTemplates")){try{var id=UUID.fromString(value.asText());if(templates.existsById(id)){templates.deleteById(id);templateCount++;}}catch(IllegalArgumentException ignored){}}return JsonNodeFactory.instance.objectNode().put("quotationsRemoved",recordCount).put("templatesRemoved",templateCount);}
    private String owner(JsonNode item,JsonNode mappings,String actor,boolean allowActorFallback){var legacy=item.path("ownerAccount").asText(item.path("salespersonAccount").asText(item.path("owner").path("account").asText(item.path("salespersonName").asText("")))).trim();if(legacy.isEmpty()&&!allowActorFallback)throw AppException.unprocessable("历史报价缺少可映射的业务员账号");var mapped=legacy.isEmpty()?actor:mappings.path(legacy).asText(legacy);if(!users.existsByAccountIgnoreCase(mapped))throw AppException.unprocessable("历史数据账号无法映射到报价生产账号："+legacy);return mapped.toUpperCase(Locale.ROOT);}
    private static UUID stable(String source,String type,String legacy){return UUID.nameUUIDFromBytes((source+":"+type+":"+legacy).getBytes(StandardCharsets.UTF_8));}
    private static Instant parseTime(String value,Instant fallback){try{return value==null||value.isBlank()?fallback:Instant.parse(value);}catch(Exception ignored){return fallback;}}
}
