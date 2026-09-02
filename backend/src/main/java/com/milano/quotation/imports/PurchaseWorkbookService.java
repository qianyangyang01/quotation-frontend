package com.milano.quotation.imports;

import com.milano.quotation.common.AppException;
import com.milano.quotation.purchase.PurchaseProductRepository;
import com.milano.quotation.storage.AssetStorageService;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.InputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

@Service
public class PurchaseWorkbookService {
    public static final List<String> HEADERS=PurchaseWorkbookSchema.LEGACY_HEADERS;
    public static final List<String> INTERNATIONAL_HEADERS=PurchaseWorkbookSchema.INTERNATIONAL_HEADERS;
    private final ImportJobRepository jobs;private final PurchaseImportRowRepository rows;private final PurchaseProductRepository products;
    private final AssetStorageService storage;private final ObjectMapper json;private final PurchaseImportRowMapper rowMapper;
    @org.springframework.beans.factory.annotation.Autowired
    public PurchaseWorkbookService(ImportJobRepository jobs,PurchaseImportRowRepository rows,PurchaseProductRepository products,AssetStorageService storage,ObjectMapper json,PurchaseImportRowMapper rowMapper){this.jobs=jobs;this.rows=rows;this.products=products;this.storage=storage;this.json=json;this.rowMapper=rowMapper;}
    PurchaseWorkbookService(ImportJobRepository jobs,PurchaseImportRowRepository rows,PurchaseProductRepository products,AssetStorageService storage,ObjectMapper json){this(jobs,rows,products,storage,json,new PurchaseImportRowMapper(json));}

    @Transactional
    public Preview preview(MultipartFile file,String actor){
        validateFile(file);
        try(var workbook=new XSSFWorkbook(file.getInputStream())){
            var formatter=new DataFormatter(Locale.CHINA);var evaluator=workbook.getCreationHelper().createFormulaEvaluator();var jobId=UUID.randomUUID();
            var parsed=new ArrayList<ParsedRow>();var allIssues=json.createArrayNode();var skuRows=new HashMap<String,List<ParsedRow>>();
            int ignored=0,productImages=0,physicalImages=0,sheetCount=0;
            for(int sheetIndex=0;sheetIndex<workbook.getNumberOfSheets();sheetIndex++){
                var sheet=workbook.getSheetAt(sheetIndex);var sheetName=safeSheetName(sheet.getSheetName());
                PurchaseWorkbookSchema schema=null;for(int candidate=0;candidate<Math.min(StreamingPurchaseWorkbookReader.HEADER_SCAN_ROWS,sheet.getLastRowNum()+1);candidate++){var headers=values(sheet.getRow(candidate),formatter,evaluator);schema=PurchaseWorkbookSchema.identifyOrNull(headers,candidate+1);if(schema!=null)break;}if(schema==null)continue;sheetCount++;
                var pictures=pictures(sheet);
                for(int rowIndex=schema.headerRow();rowIndex<=sheet.getLastRowNum();rowIndex++){
                    int excelRow=rowIndex+1;var values=values(sheet.getRow(rowIndex),formatter,evaluator);
                    var productPicture=pictures.get(excelRow+":"+schema.productImage());var physicalPicture=pictures.get(excelRow+":"+schema.physicalImage());
                    if(StreamingPurchaseWorkbookReader.isDefaultOnly(values,schema)&&productPicture==null&&physicalPicture==null){if(Arrays.stream(values).anyMatch(v->v!=null&&!v.isBlank()))ignored++;continue;}
                    var mapped=rowMapper.map(jobId.toString().substring(0,8).toUpperCase(Locale.ROOT),sheetIndex+1,sheetName,excelRow,values,schema);var payload=mapped.payload();
                    UUID productAsset=null,physicalAsset=null;
                    if(!mapped.sku().isBlank()&&mapped.errors().isEmpty()&&productPicture!=null){var asset=storage.storeTemporaryImage(productPicture.getData(),mapped.sku()+"-product."+productPicture.suggestFileExtension(),jobId);productAsset=asset.id;payload.put("productImage","/api/v1/assets/"+asset.id);payload.put("image","/api/v1/assets/"+asset.id);productImages++;}
                    if(!mapped.sku().isBlank()&&mapped.errors().isEmpty()&&physicalPicture!=null){var asset=storage.storeTemporaryImage(physicalPicture.getData(),mapped.sku()+"-physical."+physicalPicture.suggestFileExtension(),jobId);physicalAsset=asset.id;payload.put("physicalImage","/api/v1/assets/"+asset.id);physicalImages++;}
                    var item=new ParsedRow(sheetName,excelRow,mapped.sku(),payload,productAsset,physicalAsset,new ArrayList<>(mapped.errors()),mapped.warnings());parsed.add(item);
                    if(!mapped.sku().isBlank())skuRows.computeIfAbsent(mapped.sku(),key->new ArrayList<>()).add(item);
                }
            }
            var duplicateSkus=new HashSet<String>();var duplicateGroups=json.createArrayNode();
            for(var group:skuRows.entrySet())if(group.getValue().size()>1){
                duplicateSkus.add(group.getKey());var duplicate=duplicateGroups.addObject().put("sku",group.getKey());var choices=duplicate.putArray("choices");
                for(var item:group.getValue()){choices.addObject().put("sourceSheet",item.sourceSheet()).put("sourceRow",item.sourceRow());issue(allIssues,item,"SKU*","同一文件内SKU "+group.getKey()+"重复，请明确选择保留记录","error");}
            }
            if(sheetCount==0)throw AppException.unprocessable("整个工作簿未找到SKU列，无法识别采购数据表");
            int pending=0;for(var item:parsed){if(!item.payload().path("quoteReady").asBoolean(false))pending++;for(var warning:item.warnings())issue(allIssues,item,"",warning,"warning");for(var error:item.errors())issue(allIssues,item,"SKU*",error,"error");}
            long blockingErrorCount=parsed.stream().mapToLong(item->item.errors().size()).sum();long errorCount=count(allIssues,"error"),warningCount=count(allIssues,"warning");var summary=json.createObjectNode();
            summary.put("totalRows",parsed.size()).put("added",parsed.stream().filter(item->!item.sku().isBlank()&&products.findBySku(item.sku()).isEmpty()).count()).put("updated",parsed.stream().filter(item->!item.sku().isBlank()&&products.findBySku(item.sku()).isPresent()).count());
            summary.put("pending",pending).put("ignoredRows",ignored).put("generatedSku",parsed.stream().filter(item->"system".equals(item.payload().path("skuOrigin").asText())).count()).put("productImages",productImages).put("physicalImages",physicalImages).put("skipped",0).put("sheetCount",sheetCount).put("errorCount",errorCount).put("blockingErrorCount",blockingErrorCount).put("warningCount",warningCount).put("canConfirm",!parsed.isEmpty()&&blockingErrorCount==0&&duplicateSkus.isEmpty()).set("duplicateGroups",duplicateGroups).set("issues",allIssues);
            var job=new ImportJob();job.id=jobId;job.jobType="purchase-xlsx";job.status="preview";job.requestedBy=actor;job.sourceName=safeName(file.getOriginalFilename());job.sourceHash=sha(file);job.payload=summary;job.createdAt=Instant.now();job.updatedAt=job.createdAt;jobs.save(job);
            var entities=new ArrayList<PurchaseImportRow>();for(var item:parsed){var entity=new PurchaseImportRow();entity.id=UUID.randomUUID();entity.jobId=job.id;entity.sourceSheet=item.sourceSheet();entity.sourceRow=item.sourceRow();entity.sku=item.sku().isBlank()?"INVALID-"+Math.abs(item.sourceSheet().hashCode())+"-R"+item.sourceRow():item.sku();entity.payload=item.payload();entity.productAssetId=item.productAssetId();entity.physicalAssetId=item.physicalAssetId();entity.validationStatus=!item.errors().isEmpty()?"error":duplicateSkus.contains(item.sku())?"conflict":"valid";entity.importAction="valid".equals(entity.validationStatus)?(products.findBySku(item.sku()).isPresent()?"update":"insert"):"skip";entity.errorMessage=!item.errors().isEmpty()?String.join("；",item.errors()):duplicateSkus.contains(item.sku())?"同一文件内SKU重复，待选择保留记录":null;entity.createdAt=Instant.now();entities.add(entity);}rows.saveAll(entities);
            return new Preview(job.id,job.sourceName,parsed.stream().map(ParsedRow::payload).toList(),allIssues,summary);
        }catch(AppException e){throw e;}catch(Exception e){throw AppException.unprocessable("Excel文件解析失败："+safeMessage(e));}
    }

    private static String[] values(Row row,DataFormatter formatter,FormulaEvaluator evaluator){int width=row==null?0:Math.max(0,row.getLastCellNum());var out=new String[width];Arrays.fill(out,"");if(row==null)return out;for(int i=0;i<width;i++){var cell=row.getCell(i);if(cell!=null)out[i]=formatter.formatCellValue(cell,evaluator).trim();}return out;}
    private static Map<String,XSSFPictureData> pictures(Sheet raw){var result=new HashMap<String,XSSFPictureData>();if(!(raw instanceof XSSFSheet sheet))return result;var drawing=sheet.getDrawingPatriarch();if(drawing==null)return result;for(var shape:drawing.getShapes())if(shape instanceof XSSFPicture picture){var anchor=picture.getClientAnchor();int column=anchor.getCol1(),row=anchor.getRow1()+1;if(row>=2)result.put(row+":"+column,picture.getPictureData());}return result;}
    private static void issue(ArrayNode issues,ParsedRow row,String field,String message,String level){var node=issues.addObject();node.put("sourceSheet",row.sourceSheet()).put("row",row.sourceRow()).put("field",field).put("message",message).put("level",level);}
    private static long count(ArrayNode issues,String level){long result=0;for(var issue:issues)if(level.equals(issue.path("level").asText()))result++;return result;}
    private static void validateFile(MultipartFile file){if(file.isEmpty()||file.getOriginalFilename()==null||!file.getOriginalFilename().toLowerCase(Locale.ROOT).endsWith(".xlsx"))throw AppException.unprocessable("请选择.xlsx格式的采购模板");if(file.getSize()>30L*1024*1024)throw AppException.unprocessable("Excel文件不能超过30MB");}
    private static String sha(MultipartFile file)throws Exception{try(InputStream input=file.getInputStream()){var digest=MessageDigest.getInstance("SHA-256");return HexFormat.of().formatHex(digest.digest(input.readAllBytes()));}}
    private static String safeName(String value){var name=value==null?"purchase.xlsx":value.replaceAll("[\\r\\n\\\\/]","_");return name.substring(0,Math.min(255,name.length()));}
    private static String safeSheetName(String value){var name=value==null?"未命名工作表":value.trim();return name.substring(0,Math.min(128,name.length()));}
    private static String safeMessage(Exception e){var text=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();return text.substring(0,Math.min(240,text.length()));}
    private record ParsedRow(String sourceSheet,int sourceRow,String sku,ObjectNode payload,UUID productAssetId,UUID physicalAssetId,List<String>errors,List<String>warnings){}
    public record Preview(UUID jobId,String fileName,List<ObjectNode>records,ArrayNode issues,ObjectNode summary){}
}
