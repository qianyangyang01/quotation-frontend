package com.milano.quotation.migration;

import com.milano.quotation.common.AppException;
import com.milano.quotation.finance.FinanceMigrationService;
import com.milano.quotation.logistics.LogisticsService;
import com.milano.quotation.purchase.PurchaseProductService;
import com.milano.quotation.quote.QuotationMigrationService;
import com.milano.quotation.storage.AssetStorageService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.*;

@Service
class BusinessMigrationExecutor {
    private final PurchaseProductService purchase;private final FinanceMigrationService finance;private final LogisticsService logistics;private final QuotationMigrationService quotations;private final AssetStorageService storage;
    BusinessMigrationExecutor(PurchaseProductService purchase,FinanceMigrationService finance,LogisticsService logistics,QuotationMigrationService quotations,AssetStorageService storage){this.purchase=purchase;this.finance=finance;this.logistics=logistics;this.quotations=quotations;this.storage=storage;}

    @Transactional
    ObjectNode execute(BusinessMigrationBatch batch,String actor){var report=batch.report;var approved=new HashSet<String>();report.path("approvedEntryKeys").forEach(value->approved.add(value.asText()));if(approved.isEmpty())throw AppException.unprocessable("迁移白名单不能为空");
        var execution=JsonNodeFactory.instance.objectNode().put("batchId",batch.id.toString()).put("sourceHash",batch.sourceHash);var createdSkus=execution.putArray("createdSkus");var assets=execution.putArray("assetIds");var financeChanges=execution.putArray("financeChanges");var logisticsEntries=JsonNodeFactory.instance.arrayNode();var quoteResult=JsonNodeFactory.instance.objectNode();var mappings=report.path("ownerMappings");var resolutions=report.path("conflictResolutions");
        for(var entry:report.path("entries")){var key=BusinessMigrationService.entryKey(entry);if(!approved.contains(key))continue;var category=entry.path("category").asText();var value=entry.path("value");switch(category){
            case"logistics"->logisticsEntries.add(entry.deepCopy());
            case"purchase"->migratePurchase(value,batch,createdSkus,assets);
            case"finance"->{var financeKey=financeKey(entry.path("key").asText());var replace="replace".equals(resolutions.path(key).asText());financeChanges.add(finance.apply(financeKey,normalizeFinance(financeKey,value),replace));}
            case"quotation-template"->merge(quoteResult,quotations.applyTemplates(value,mappings,actor,batch.sourceHash));
            case"quotation-record"->merge(quoteResult,quotations.applyRecords(value,mappings,actor,batch.sourceHash));
            case"customer"->throw AppException.unprocessable("当前迁移报告包含尚未完成字段映射的主数据："+category);
            case"supplier"->throw AppException.unprocessable("供应商主数据功能已下线，禁止迁移该类别");
            default->throw AppException.unprocessable("无法识别的迁移数据类别："+category);}}
        if(!logisticsEntries.isEmpty())execution.set("logistics",logistics.importMigrationDrafts(logisticsEntries,actor));else execution.set("logistics",JsonNodeFactory.instance.objectNode());execution.set("quotations",quoteResult);var assetIds=uuidList(assets);storage.publish(assetIds);execution.put("productsCreated",createdSkus.size()).put("assetsPublished",assetIds.size()).put("financeChanged",countChanged(financeChanges));return execution;}

    @Transactional
    ObjectNode rollback(BusinessMigrationBatch batch){if(!"completed".equals(batch.status))throw AppException.conflict("只有已完成迁移批次可以回滚");var execution=batch.report.path("execution");var result=JsonNodeFactory.instance.objectNode();result.set("logistics",logistics.rollbackMigration(execution));result.set("quotations",quotations.rollback(execution.path("quotations")));finance.rollback(execution.path("financeChanges"));int removed=0;for(var sku:execution.path("createdSkus")){try{purchase.delete(sku.asText());removed++;}catch(AppException error){if(!error.getMessage().contains("不存在"))throw error;}}storage.retire(uuidList(execution.path("assetIds")),batch.id);result.put("productsRemoved",removed).put("assetsRetired",execution.path("assetIds").size()).put("financeRestored",execution.path("financeChanges").size());return result;}

    private void migratePurchase(JsonNode value,BusinessMigrationBatch batch,ArrayNode createdSkus,ArrayNode assets){var rows=value.isArray()?value:JsonNodeFactory.instance.arrayNode().add(value);for(var source:rows){if(!(source instanceof ObjectNode row))throw AppException.unprocessable("采购迁移数据格式错误");var sku=row.path("sku").asText("").trim().toUpperCase(Locale.ROOT).replaceAll("\\s+","");if(permanentlyExcludedSku(sku)||looksLikeTest(row))continue;if(sku.isBlank())throw AppException.unprocessable("采购迁移数据缺少SKU");if(purchase.exists(sku)){var existing=purchase.get(sku);if(batch.sourceHash.equals(existing.path("legacyMigrationSource").asText()))continue;throw AppException.conflict("生产已存在不同内容的SKU："+sku);}var payload=(ObjectNode)row.deepCopy();payload.put("sku",sku);payload.put("legacyMigrationSource",batch.sourceHash);var productAsset=stageImage(payload,"productImage","product",batch.id,assets);var physicalAsset=stageImage(payload,"physicalImage","physical",batch.id,assets);if(productAsset!=null)payload.put("image","/api/v1/assets/"+productAsset);purchase.upsertImported(payload,productAsset,physicalAsset);createdSkus.add(sku);}}
    private UUID stageImage(ObjectNode payload,String field,String type,UUID batchId,ArrayNode assets){var value=payload.path(field).asText("");if(!value.startsWith("data:image/"))return null;var comma=value.indexOf(',');if(comma<0||!value.substring(0,comma).contains(";base64"))throw AppException.unprocessable("SKU "+payload.path("sku").asText()+" 的图片Base64格式错误");try{var bytes=Base64.getDecoder().decode(value.substring(comma+1));var asset=storage.storeTemporaryImageIndependent(bytes,payload.path("sku").asText()+"-"+type,batchId);payload.put(field,"/api/v1/assets/"+asset.id);if(type.equals("product"))payload.put("image","/api/v1/assets/"+asset.id);if(!contains(assets,asset.id.toString()))assets.add(asset.id.toString());return asset.id;}catch(IllegalArgumentException error){throw AppException.unprocessable("SKU "+payload.path("sku").asText()+" 的图片Base64格式错误");}}
    static boolean permanentlyExcludedSku(String sku){return sku.matches("(?i)^(TESTP|TEST|DEMO|MOCK)[A-Z0-9._/-]*$")||sku.startsWith("AUTO-");}
    static boolean looksLikeTest(JsonNode row){var text=row.toString();return text.length()<2_000_000&&text.matches("(?is).*(演示|模拟|mock|demo|uat测试).*" );}
    private static String financeKey(String legacy){var value=legacy.toLowerCase(Locale.ROOT);if(value.contains("country-classification"))return"country-classification";if(value.contains("logistics-attribute-policies")||value.contains("channel-policies"))return"channel-policies";if(value.contains("customer-grade"))return"customer-grades";if(value.contains("exchange-rate"))return"exchange-rate";if(value.contains("tax-settings"))return"tax-settings";throw AppException.unprocessable("无法映射财务设置："+legacy);}
    private static JsonNode normalizeFinance(String key,JsonNode value){if(key.equals("country-classification")&&value.path("countries").isArray())return value.path("countries").deepCopy();if(key.equals("channel-policies")&&value.path("policies").isArray())return value.path("policies").deepCopy();if(key.equals("customer-grades")&&value.path("grades").isArray())return value.path("grades").deepCopy();if(key.equals("exchange-rate")&&value.has("usdToCny")){var result=JsonNodeFactory.instance.objectNode().set("usdCny",value.path("usdToCny").deepCopy());result.put("updatedAt",value.path("effectiveAt").asText(""));return result;}return value.deepCopy();}
    private static void merge(ObjectNode target,ObjectNode source){source.properties().forEach(entry->{if(entry.getValue().isArray()){var array=target.withArray(entry.getKey());entry.getValue().forEach(value->array.add(value.deepCopy()));}else target.set(entry.getKey(),entry.getValue().deepCopy());});}
    private static List<UUID> uuidList(JsonNode values){var ids=new ArrayList<UUID>();for(var value:values)try{ids.add(UUID.fromString(value.asText()));}catch(IllegalArgumentException ignored){}return ids;}
    private static boolean contains(ArrayNode values,String expected){for(var value:values)if(expected.equals(value.asText()))return true;return false;}
    private static int countChanged(ArrayNode changes){int count=0;for(var item:changes)if(item.path("changed").asBoolean())count++;return count;}
}
