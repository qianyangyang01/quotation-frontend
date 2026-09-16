package com.milano.quotation.logistics;

import com.milano.quotation.common.AppException;
import com.milano.quotation.storage.AssetStorageService;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellReference;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.*;

/** Repairs only proven no-discount rows in an existing draft, preserving manual prices. */
@Component
public class LogisticsSfDraftRepair {
    private static final String DISCOUNT_ERROR="顺丰折扣不是明确的有效折扣（须为0到1之间的系数、百分比或几折）";
    private static final Set<String> BLOCKERS=Set.of("顺丰有效运费缺失或无效，禁止回退原价或按零计价","公斤价计费结构不完整");
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final AssetStorageService storage;
    private final LogisticsParserAliases aliases;

    public LogisticsSfDraftRepair(JdbcClient jdbc,ObjectMapper mapper,AssetStorageService storage,LogisticsParserAliases aliases) {
        this.jdbc=jdbc;this.mapper=mapper;this.storage=storage;this.aliases=aliases;
    }

    ArrayNode repair(ObjectNode payload) {
        var audit=mapper.createArrayNode();
        if(!payload.path("providerName").asText().equals("顺丰"))return audit;
        boolean needed=false;
        for(var row:payload.path("rows"))if(candidate(payload,row)){needed=true;break;}
        if(!needed)return audit;
        // Legacy drafts did not record settlement-column metadata. Inspect the retained
        // workbook instead of guessing that an absent field means an empty Excel cell.
        var batchId=payload.path("batchId").asText();
        if(batchId.isBlank())throw AppException.unprocessable("需读取原表确认顺丰折扣为空，当前草稿缺少导入来源");
        var raw=jdbc.sql("select payload::text from logistics_import_batch where id=:id")
                .param("id",UUID.fromString(batchId)).query(String.class).optional();
        if(raw.isEmpty())throw AppException.unprocessable("顺丰原表导入记录不存在，无法确认不打折规则");
        var files=mapper.readTree(raw.get()).path("files");
        int index=payload.path("sourceFileIndex").asInt(0);
        if(index<0||index>=files.size())throw AppException.unprocessable("顺丰原表不存在，无法确认不打折规则");
        var file=files.get(index);
        if(file.path("lifecycleStatus").asText().equals("deleted")||(!file.path("deletedAt").isNull()&&!file.path("deletedAt").asText().isBlank())||file.path("objectKey").asText().isBlank())
            throw AppException.unprocessable("顺丰原表已清理，无法自动确认空折扣；请核对原表后重新导入");
        try(var input=storage.openRaw(file.path("objectKey").asText());var book=WorkbookFactory.create(input)) {
            return repair(payload,book);
        } catch(AppException e){throw e;}
        catch(Exception e){throw AppException.unprocessable("无法读取顺丰原表核对空折扣，请稍后重试；原有阻断问题已保留");}
    }

    ArrayNode repair(ObjectNode payload,Workbook book) {
        var audit=mapper.createArrayNode();
        if(!payload.path("providerName").asText().equals("顺丰"))return audit;
        var resolved=new ArrayList<JsonNode>();
        for(var value:payload.path("rows")) {
            if(!candidate(payload,value))continue;
            var row=(ObjectNode)value;
            var sheet=book.getSheet(row.path("sourceSheet").asText());
            int sourceRow=row.path("sourceRow").asInt()-1;
            if(sheet==null||sourceRow<0||row.path("sourceOriginalRateCell").asText().isBlank())continue;
            var originalRef=new CellReference(row.path("sourceOriginalRateCell").asText());
            if(originalRef.getRow()!=sourceRow)continue;
            boolean confirmed=false;
            for(int header=sourceRow-1;header>=0;header--) {
                var headerRow=sheet.getRow(header);if(headerRow==null)continue;
                if(!aliases.matches("顺丰","pricePerKg",label(cell(sheet,header,originalRef.getCol()))))continue;
                int discount=-1,settlement=-1;boolean ambiguous=false;
                for(var headerCell:headerRow) {
                    var text=LogisticsSourceParser.clean(label(headerCell));
                    if(text.equals("折扣")||text.equals("折扣率")){if(discount>=0)ambiguous=true;discount=headerCell.getColumnIndex();}
                    if(aliases.matches("顺丰","settlementRate",text)){if(settlement>=0)ambiguous=true;settlement=headerCell.getColumnIndex();}
                }
                // Require both columns in legacy workbooks, with real blank cells.
                if(!ambiguous&&discount>=0&&settlement>=0&&blank(cell(sheet,sourceRow,discount))&&blank(cell(sheet,sourceRow,settlement))) {
                    var original=cell(sheet,sourceRow,originalRef.getCol());
                    confirmed=original!=null&&original.getCellType()==CellType.NUMERIC&&Double.isFinite(original.getNumericCellValue())&&original.getNumericCellValue()>0;
                }
                break;
            }
            if(!confirmed)continue;
            var entry=audit.addObject().put("sourceSheet",sheet.getSheetName()).put("sourceRow",sourceRow+1)
                    .put("reason","原表折扣率和折后运费均为空，采用原运费；保留已保存的人工价格")
                    .put("pricePerKg",row.path("pricePerKg").decimalValue());
            entry.put("previousPricingBasis",row.path("sourcePricingBasis").asText());
            row.put("sourcePricingBasis","original").put("sourceDiscountEmpty",true).put("sourceSettlementEmpty",true);
            for(var field:List.of("pendingReason","blockingReason"))row.put(field,Arrays.stream(row.path(field).asText().split("；"))
                    .filter(reason->!BLOCKERS.contains(reason)).collect(java.util.stream.Collectors.joining("；")));
            resolved.add(row);
        }
        var kept=mapper.createArrayNode();
        for(var issue:payload.path("issues"))if(!knownIssue(issue)||resolved.stream().noneMatch(row->sameSource(issue,row)))kept.add(issue);
        payload.set("issues",kept);
        return audit;
    }

    private static boolean candidate(JsonNode payload,JsonNode row) {
        if(!row.path("pricingModel").asText().equals("per-kg")||!row.path("pricePerKg").isNumber()||row.path("pricePerKg").decimalValue().signum()<=0
                ||row.path("firstWeightPrice").asDouble()!=0||row.path("intervalPrice").asDouble()!=0)return false;
        for(var issue:payload.path("issues"))if(issue.path("message").asText().equals(DISCOUNT_ERROR)&&sameSource(issue,row))return true;
        return false;
    }
    private static boolean sameSource(JsonNode issue,JsonNode row) {
        return issue.path("row").asInt()>0&&issue.path("row").asInt()==row.path("sourceRow").asInt()
                &&!issue.path("sourceSheet").asText().isBlank()&&issue.path("sourceSheet").asText().equals(row.path("sourceSheet").asText());
    }
    private static boolean knownIssue(JsonNode issue) {
        return switch(issue.path("field").asText()) {
            case "pricePerKg" -> Set.of(DISCOUNT_ERROR,"顺丰有效运费必须大于0").contains(issue.path("message").asText());
            case "计费方式" -> issue.path("message").asText().equals("计费方式与基础价格字段不一致");
            default -> false;
        };
    }
    private static Cell cell(Sheet sheet,int row,int column) {
        for(var merged:sheet.getMergedRegions())if(merged.isInRange(row,column)){row=merged.getFirstRow();column=merged.getFirstColumn();break;}
        return sheet.getRow(row)==null?null:sheet.getRow(row).getCell(column);
    }
    private static String label(Cell cell){return cell!=null&&cell.getCellType()==CellType.STRING?cell.getStringCellValue():"";}
    private static boolean blank(Cell cell){return cell==null||cell.getCellType()==CellType.BLANK||(cell.getCellType()==CellType.STRING&&LogisticsSourceParser.clean(cell.getStringCellValue()).isBlank());}
}
