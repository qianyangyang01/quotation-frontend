package com.milano.quotation.purchase;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import com.milano.quotation.common.AppException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import com.milano.quotation.storage.AssetStorageService;
import org.springframework.web.multipart.MultipartFile;

@Service
public class PurchaseProductService {
    public static final String CATALOG_PENDING_TEMPLATE = "pending_template";
    public static final String CATALOG_READY = "ready";
    public static final String CATALOG_DISABLED = "disabled";
    private final PurchaseProductRepository products; private final PurchaseProductImageRepository images; private final AssetStorageService storage;
    public PurchaseProductService(PurchaseProductRepository products,PurchaseProductImageRepository images,AssetStorageService storage) { this.products = products; this.images=images; this.storage=storage; }

    @Transactional(readOnly=true) public Page<JsonNode> page(String query,Pageable pageable) { return products.search(query==null?"":query.trim(),pageable).map(this::view); }
    @Transactional(readOnly=true) public JsonNode get(String sku) { return products.findBySku(normalizeSku(sku)).map(this::view).orElseThrow(()->AppException.notFound("商品不存在")); }
    @Transactional(readOnly=true) public boolean exists(String sku) { return products.findBySku(normalizeSku(sku)).isPresent(); }
    @Transactional(readOnly=true) public long readyCount() { return products.countByQuoteReadyTrue(); }
    @Transactional(readOnly=true) public Stats stats() {var total=products.count();var ready=products.countByQuoteReadyTrue();return new Stats(total,ready,total-ready,products.countGeneratedSku());}
    @Transactional(readOnly=true) public boolean isQuoteReady(String sku) { return products.findBySku(normalizeSku(sku)).map(row -> row.quoteReady).orElse(false); }

    @Transactional
    public JsonNode upsert(JsonNode input) {
        return upsert(input, true, null, null);
    }

    private JsonNode upsert(JsonNode input, boolean requireVersionForExisting, String requestedCatalogState, String sourceHash) {
        if (!(input instanceof ObjectNode object)) throw AppException.unprocessable("商品数据格式错误");
        var sku = normalizeSku(object.path("sku").asText());
        externalizeImage(object,"productImage","product"); externalizeImage(object,"physicalImage","physical");
        if(object.path("productImage").asText("").isBlank()&&!object.path("image").asText("").isBlank())object.put("productImage",object.path("image").asText());
        validatePayload(object);
        object.put("sku", sku);
        var existing=products.findBySku(sku);
        if(existing.isPresent()&&requireVersionForExisting&&(!object.has("_version")||object.path("_version").asLong(-1)!=existing.get().version))throw AppException.conflict("商品 "+sku+" 已被其他用户修改，请刷新后重试");
        var catalogState = requestedCatalogState != null ? requestedCatalogState
                : existing.map(row -> row.catalogState).orElse(CATALOG_READY);
        if (!List.of(CATALOG_PENDING_TEMPLATE, CATALOG_READY, CATALOG_DISABLED).contains(catalogState)) throw AppException.unprocessable("商品目录状态不合法");
        if (isReservedSku(sku) && CATALOG_READY.equals(catalogState)) catalogState = CATALOG_PENDING_TEMPLATE;
        var quoteReady = CATALOG_READY.equals(catalogState) && completeForQuotation(object) && !isReservedSku(sku);
        applyDerivedState(object, catalogState, quoteReady);
        object.remove(java.util.List.of("_version","_updatedAt"));
        var finalCatalogState = catalogState; var finalQuoteReady = quoteReady;
        var row = existing.orElseGet(() -> PurchaseProduct.create(sku, object.deepCopy(), finalCatalogState, finalQuoteReady, normalizeSourceHash(sourceHash)));
        row.payload = object.deepCopy(); row.catalogState=catalogState; row.quoteReady=quoteReady;
        if(sourceHash!=null)row.sourceHash=normalizeSourceHash(sourceHash);
        row.updatedAt = Instant.now(); products.saveAndFlush(row); linkFromUrl(row.id,object.path("productImage").asText(""),"product");linkFromUrl(row.id,object.path("physicalImage").asText(""),"physical");return view(row);
    }

    @Transactional public List<JsonNode> upsertAll(List<JsonNode> rows) {
        if (rows.size() > 5000) throw AppException.unprocessable("单次确认最多5000条商品，请分批导入");
        return rows.stream().map(this::upsert).toList();
    }

    @Transactional public void delete(String sku) {
        var normalized = normalizeSku(sku); if (products.findBySku(normalized).isEmpty()) throw AppException.notFound("商品不存在"); products.deleteBySku(normalized);
    }

    @Transactional public JsonNode uploadImage(String sku,String type,MultipartFile file){
        if(!List.of("product","physical").contains(type))throw AppException.unprocessable("图片类型不合法");var product=products.findBySku(normalizeSku(sku)).orElseThrow(()->AppException.notFound("商品不存在"));
        try{var asset=storage.storeImage(file.getBytes(),file.getOriginalFilename());link(product.id,asset.id,type);var payload=(ObjectNode)product.payload;payload.put(type.equals("product")?"productImage":"physicalImage","/api/v1/assets/"+asset.id);if(type.equals("product"))payload.put("image","/api/v1/assets/"+asset.id);product.updatedAt=Instant.now();return view(product);}catch(java.io.IOException e){throw AppException.unprocessable("图片读取失败");}
    }

    @Transactional public JsonNode upsertImported(JsonNode payload,UUID productAssetId,UUID physicalAssetId){
        // The parser already writes the staged asset URLs into the payload and
        // upsert() materializes those links through linkFromUrl(). Linking the
        // same assets again here leaves a delete/insert pair in one Hibernate
        // batch and violates the unique product/asset/type constraint on real
        // PostgreSQL.
        return upsert(payload,false,null,null);
    }
    @Transactional public JsonNode upsertImported(JsonNode payload,UUID productAssetId,UUID physicalAssetId,String importMode,String sourceHash){
        var catalogState="pending_template".equals(importMode)?CATALOG_PENDING_TEMPLATE:CATALOG_READY;
        return upsert(payload,false,catalogState,sourceHash);
    }

    @Transactional public JsonNode promote(String sourceSku,String targetSku,long expectedVersion){
        var source=normalizeSku(sourceSku);var target=normalizeSku(targetSku);var row=products.findBySku(source).orElseThrow(()->AppException.notFound("商品不存在"));
        if(row.version!=expectedVersion)throw AppException.conflict("商品已被其他用户修改，请刷新后重试");
        if(!CATALOG_PENDING_TEMPLATE.equals(row.catalogState))throw AppException.conflict("只有模板待补全商品可以确认转正式");
        if(isReservedSku(target))throw AppException.unprocessable("正式商品必须改为非 TEST/DEMO/AUTO 的业务SKU");
        if(!source.equals(target)&&products.findBySku(target).isPresent())throw AppException.conflict("目标SKU已存在："+target);
        var payload=(ObjectNode)row.payload.deepCopy();payload.put("sku",target);validatePayload(payload);
        if(!completeForQuotation(payload))throw AppException.unprocessable("重量、长宽高、起订量或采购价尚未补齐，不能转正式");
        row.sku=target;row.catalogState=CATALOG_READY;row.quoteReady=true;row.updatedAt=Instant.now();applyDerivedState(payload,CATALOG_READY,true);row.payload=payload;
        products.saveAndFlush(row);return view(row);
    }
    @Transactional public void linkAsset(String sku,UUID assetId,String type){if(!List.of("product","physical").contains(type))throw AppException.unprocessable("图片类型不合法");var product=products.findBySku(normalizeSku(sku)).orElseThrow(()->AppException.notFound("SKU "+sku+" 不存在"));link(product.id,assetId,type);var payload=(ObjectNode)product.payload;payload.put(type.equals("product")?"productImage":"physicalImage","/api/v1/assets/"+assetId);if(type.equals("product"))payload.put("image","/api/v1/assets/"+assetId);product.updatedAt=Instant.now();}
    private void link(UUID productId,UUID assetId,String type){
        var current=images.findFirstByProductIdAndImageTypeOrderBySortOrderAsc(productId,type);
        if(current.isPresent()&&current.get().assetId.equals(assetId))return;
        if(current.isPresent()){images.deleteByProductIdAndImageType(productId,type);images.flush();}
        var link=new PurchaseProductImage();link.id=UUID.randomUUID();link.productId=productId;link.assetId=assetId;link.imageType=type;link.sortOrder=0;images.save(link);
    }

    private JsonNode view(PurchaseProduct row) {
        var object = (ObjectNode) row.payload.deepCopy(); object.put("sku", row.sku); object.put("_version", row.version);
        object.put("catalogState", row.catalogState);object.put("quoteReady",row.quoteReady);object.put("_updatedAt", row.updatedAt.toString()); return object;
    }
    private static String normalizeSku(String sku) {
        var value = sku == null ? "" : sku.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", "");
        if (value.isEmpty() || value.length() > 96 || !value.matches("[A-Z0-9._/-]+")) throw AppException.unprocessable("SKU格式不合法"); return value;
    }
    private static void validatePayload(ObjectNode object) {
        for (String field : List.of("productImage", "physicalImage", "image")) {
            var value = object.path(field).asText("");
            if (value.startsWith("data:")) throw AppException.unprocessable("图片不能以Base64保存到数据库，请使用图片上传接口");
        }
        if (object.toString().length() > 1_000_000) throw AppException.unprocessable("单条商品数据过大");
    }
    private static boolean completeForQuotation(ObjectNode object){return positive(object,"weightG")&&positive(object,"lengthCm")&&positive(object,"widthCm")&&positive(object,"heightCm")&&positive(object,"minOrderQty")&&nonNegative(object,"purchasePriceCny");}
    private static boolean positive(ObjectNode object,String field){var value=object.get(field);return value!=null&&value.isNumber()&&value.asDouble()>0;}
    private static boolean nonNegative(ObjectNode object,String field){var value=object.get(field);return value!=null&&value.isNumber()&&value.asDouble()>=0;}
    private static boolean isReservedSku(String sku){return sku.matches("(?i)^(TESTP|TEST|DEMO|MOCK)[A-Z0-9._/-]*$")||sku.startsWith("AUTO-");}
    private static void applyDerivedState(ObjectNode object,String state,boolean quoteReady){object.put("catalogState",state);object.put("quoteReady",quoteReady);object.put("status",CATALOG_PENDING_TEMPLATE.equals(state)?"模板待补全（不可报价）":CATALOG_DISABLED.equals(state)?"已停用":quoteReady?"资料完整":"待补充资料");}
    private static String normalizeSourceHash(String value){if(value==null||value.isBlank())return null;var normalized=value.trim().toLowerCase(Locale.ROOT);if(!normalized.matches("[0-9a-f]{64}"))throw AppException.unprocessable("导入来源SHA-256不合法");return normalized;}
    private void externalizeImage(ObjectNode object,String field,String type){var value=object.path(field).asText("");if(!value.startsWith("data:"))return;var marker=value.indexOf(",");if(marker<0||!value.substring(0,marker).contains(";base64"))throw AppException.unprocessable("图片Base64格式错误");try{var bytes=java.util.Base64.getDecoder().decode(value.substring(marker+1));var asset=storage.storeImage(bytes,object.path("sku").asText("product")+"-"+type);object.put(field,"/api/v1/assets/"+asset.id);if(type.equals("product"))object.put("image","/api/v1/assets/"+asset.id);}catch(IllegalArgumentException e){throw AppException.unprocessable("图片Base64格式错误");}}
    private void linkFromUrl(UUID productId,String value,String type){var prefix="/api/v1/assets/";if(!value.startsWith(prefix))return;try{var assetId=UUID.fromString(value.substring(prefix.length()));link(productId,assetId,type);}catch(IllegalArgumentException ignored){}}
    public record Stats(long total,long ready,long pending,long generatedSku){}
}
