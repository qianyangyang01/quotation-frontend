package com.milano.quotation.purchase;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.milano.quotation.common.AppException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import com.milano.quotation.storage.AssetStorageService;
import org.springframework.web.multipart.MultipartFile;

@Service
public class PurchaseProductService {
    private final PurchaseProductRepository products; private final PurchaseProductImageRepository images; private final AssetStorageService storage;
    public PurchaseProductService(PurchaseProductRepository products,PurchaseProductImageRepository images,AssetStorageService storage) { this.products = products; this.images=images; this.storage=storage; }

    @Transactional(readOnly=true) public List<JsonNode> list() { return products.findAllByOrderByUpdatedAtDesc().stream().map(this::view).toList(); }
    @Transactional(readOnly=true) public boolean exists(String sku) { return products.findBySku(normalizeSku(sku)).isPresent(); }

    @Transactional
    public JsonNode upsert(JsonNode input) {
        return upsert(input, true);
    }

    private JsonNode upsert(JsonNode input, boolean requireVersionForExisting) {
        if (!(input instanceof ObjectNode object)) throw AppException.unprocessable("商品数据格式错误");
        var sku = normalizeSku(object.path("sku").asText());
        externalizeImage(object,"productImage","product"); externalizeImage(object,"physicalImage","physical");
        if(object.path("productImage").asText("").isBlank()&&!object.path("image").asText("").isBlank())object.put("productImage",object.path("image").asText());
        validatePayload(object);
        object.put("sku", sku);
        var existing=products.findBySku(sku);
        if(existing.isPresent()&&requireVersionForExisting&&(!object.has("_version")||object.path("_version").asLong(-1)!=existing.get().version))throw AppException.conflict("商品 "+sku+" 已被其他用户修改，请刷新后重试");
        object.remove(java.util.List.of("_version","_updatedAt"));
        var row = existing.orElseGet(() -> PurchaseProduct.create(sku, object.deepCopy()));
        row.payload = object.deepCopy(); row.updatedAt = Instant.now(); products.saveAndFlush(row); linkFromUrl(row.id,object.path("productImage").asText(""),"product");linkFromUrl(row.id,object.path("physicalImage").asText(""),"physical");return view(row);
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
        try{var asset=storage.storeImage(file.getBytes(),file.getOriginalFilename());images.deleteByProductIdAndImageType(product.id,type);var link=new PurchaseProductImage();link.id=UUID.randomUUID();link.productId=product.id;link.assetId=asset.id;link.imageType=type;link.sortOrder=0;images.save(link);var payload=(ObjectNode)product.payload;payload.put(type.equals("product")?"productImage":"physicalImage","/api/v1/assets/"+asset.id);if(type.equals("product"))payload.put("image","/api/v1/assets/"+asset.id);product.updatedAt=Instant.now();return view(product);}catch(java.io.IOException e){throw AppException.unprocessable("图片读取失败");}
    }

    @Transactional public JsonNode upsertImported(JsonNode payload,UUID productAssetId,UUID physicalAssetId){var result=upsert(payload,false);var product=products.findBySku(result.path("sku").asText()).orElseThrow();if(productAssetId!=null)link(product.id,productAssetId,"product");if(physicalAssetId!=null)link(product.id,physicalAssetId,"physical");return result;}
    @Transactional public void linkAsset(String sku,UUID assetId,String type){if(!List.of("product","physical").contains(type))throw AppException.unprocessable("图片类型不合法");var product=products.findBySku(normalizeSku(sku)).orElseThrow(()->AppException.notFound("SKU "+sku+" 不存在"));link(product.id,assetId,type);var payload=(ObjectNode)product.payload;payload.put(type.equals("product")?"productImage":"physicalImage","/api/v1/assets/"+assetId);if(type.equals("product"))payload.put("image","/api/v1/assets/"+assetId);product.updatedAt=Instant.now();}
    private void link(UUID productId,UUID assetId,String type){images.deleteByProductIdAndImageType(productId,type);var link=new PurchaseProductImage();link.id=UUID.randomUUID();link.productId=productId;link.assetId=assetId;link.imageType=type;link.sortOrder=0;images.save(link);}

    private JsonNode view(PurchaseProduct row) {
        var object = (ObjectNode) row.payload.deepCopy(); object.put("sku", row.sku); object.put("_version", row.version);
        object.put("_updatedAt", row.updatedAt.toString()); return object;
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
    private void externalizeImage(ObjectNode object,String field,String type){var value=object.path(field).asText("");if(!value.startsWith("data:"))return;var marker=value.indexOf(",");if(marker<0||!value.substring(0,marker).contains(";base64"))throw AppException.unprocessable("图片Base64格式错误");try{var bytes=java.util.Base64.getDecoder().decode(value.substring(marker+1));var asset=storage.storeImage(bytes,object.path("sku").asText("product")+"-"+type);object.put(field,"/api/v1/assets/"+asset.id);if(type.equals("product"))object.put("image","/api/v1/assets/"+asset.id);}catch(IllegalArgumentException e){throw AppException.unprocessable("图片Base64格式错误");}}
    private void linkFromUrl(UUID productId,String value,String type){var prefix="/api/v1/assets/";if(!value.startsWith(prefix))return;try{var assetId=UUID.fromString(value.substring(prefix.length()));link(productId,assetId,type);}catch(IllegalArgumentException ignored){}}
}
