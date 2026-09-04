package com.milano.quotation.logistics;

import com.milano.quotation.common.AppException;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellReference;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;

/** Original workbooks are evidence, never executable instructions. No macros/evaluator/network. */
@Service
public class LogisticsSourceParser {
    public static final String VERSION="providers-2026.09.04-v5";
    public static final long MAX_FILE_BYTES=100L*1024*1024;
    public static final List<String> PROVIDERS=List.of("花海","容鼎","通邮","万邦","云速递","递四方","极通环球","云途","燕文","顺丰");
    public static final List<String> EXTRA_HEADERS=List.of("物流商","渠道名称","货物属性","币种","计费方式","起点包含","终点包含","发货区域","计费进位KG","规则备注","来源表","来源行","待适配原因","干线费每KG");
    private static final Pattern NUM=Pattern.compile("[0-9]+(?:\\.[0-9]+)?");
    private static final Pattern RANGE=Pattern.compile("^([0-9.]+)(<=|<)(?:W|重量)(<=|<)([0-9.]+)$",Pattern.CASE_INSENSITIVE);
    private static final Pattern ETA_RANGE=Pattern.compile("(?<![0-9])([0-9]{1,3})\\s*[-—–~～至]\\s*([0-9]{1,3})\\s*(?:个)?(?:工作日|天)(?![0-9])");
    private static final Map<String,String> COUNTRIES=countries();
    private final ObjectMapper mapper;
    private final LogisticsWorkbookService standard;
    public LogisticsSourceParser(ObjectMapper mapper,LogisticsWorkbookService standard){this.mapper=mapper;this.standard=standard;}

    public ObjectNode parse(byte[] bytes,String filename) {
        if(bytes.length==0 || bytes.length>MAX_FILE_BYTES || filename==null || !filename.toLowerCase(Locale.ROOT).matches(".*\\.xlsx?$"))
            throw AppException.unprocessable("单个物流文件必须是100MB以内的.xls或.xlsx");
        var result=mapper.createObjectNode().put("fileName",filename).put("parserVersion",VERSION);
        var sheets=result.putArray("sheets"); var channels=new LinkedHashMap<String,ObjectNode>();
        String provider=provider(filename);
        try(var book=WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            if(book.getNumberOfSheets()>300) throw AppException.unprocessable("单个文件不能超过300张工作表");
            for(var sheet:book) {
                var source=new Source(sheet); var report=sheets.addObject().put("name",sheet.getSheetName()).put("hidden",book.isSheetHidden(book.getSheetIndex(sheet)));
                if(source.text(0,0).equals("MILANO_LOGISTICS_DIFF_V1"))throw AppException.unprocessable("价格变化表不能作为价格模板导入");
                if(source.text(0,0).equals("MILANO_LOGISTICS_METADATA_V1")){report.put("status","metadata");continue;}
                if(source.nonempty==0) { report.put("status","empty").put("message","空表，无价格数据"); continue; }
                if(sheet.getLastRowNum()>100000) throw AppException.unprocessable("工作表行数超过100000");
                var parsed=new LinkedHashMap<String,ObjectNode>();
                boolean recognized=false;
                for(int r=0;r<=sheet.getLastRowNum();r++) if(source.text(r,0).equals(LogisticsWorkbookService.HEADERS.getFirst()) && source.text(r,1).equals("国家简码")) {
                    parseStandard(source,r,provider,filename,parsed); recognized=true; break;
                }
                if(!recognized && !provider.isBlank()) {
                    if(provider.equals("通邮") && (sheet.getSheetName().contains("美国专线小包")||sheet.getSheetName().contains("加拿大专线"))) recognized=parseMatrix(source,provider,filename,parsed);
                    else recognized=parseTable(source,provider,filename,parsed);
                }
                if(!recognized || parsed.isEmpty()) {
                    var pending=channel(provider.isBlank()?"未识别物流商":provider,sheet.getSheetName().trim(),parsed);
                    pending.put("templateStatus","adapter-required");
                    issue(pending,0,"新模板待适配","非空表未匹配通用模板或已知物流商模板；原文件保留7天，新增解析器后可重试","error");
                    pending.set("sourceCells",source.raw());
                }
                report.set("sourceCells",source.raw());
                var uncovered=report.putArray("unparsedPriceRows");
                var examples=report.putArray("exampleRows");source.exampleRows.stream().sorted().forEach(r->examples.add(r+1));
                var conditional=report.putArray("conditionalPriceRows");var conditionalEvidence=new ArrayList<String>();
                for(var rawRow:sheet) {
                    int r=rawRow.getRowNum();
                    if(source.parsedRows.contains(r)||source.exampleRows.contains(r))continue;
                    boolean range=false,number=false;
                    for(var cell:rawRow){if(looksRange(source.text(r,cell.getColumnIndex())))range=true;if(cell.getCellType()==CellType.NUMERIC)number=true;}
                    if(range&&number) {
                        int auxiliary=source.auxiliaryRows.getOrDefault(r,source.auxiliaryHeader(r));
                        if(auxiliary>=0){conditional.add(r+1);conditionalEvidence.add(String.join(" | ",source.resolvedTexts(auxiliary))+" / "+String.join(" | ",source.resolvedTexts(r)));}
                        else uncovered.add(r+1);
                    }
                }
                if(!conditional.isEmpty())for(var c:parsed.values())for(var value:c.path("rows")) {
                    var row=(ObjectNode)value;pending(row,"附加或重派费用表需适配核对");
                    row.put("notes",row.path("notes").asText()+"\n[附加费用原表区域]\n"+String.join("\n",conditionalEvidence));
                }
                if(!uncovered.isEmpty())for(var c:parsed.values())issue(c,0,"未覆盖价格行","存在未完整识别的价格区域，禁止部分替换；原始行号："+uncovered,"error");
                int rows=0; int errors=0;
                for(var channel:parsed.values()) {
                    finish(channel,provider);
                    rows+=channel.path("rows").size(); errors+=channel.path("errors").asInt();
                    var key=identity(channel);
                    if(channels.containsKey(key)) {
                        var existing=channels.get(key);
                        ((ArrayNode)existing.path("rows")).addAll((ArrayNode)channel.path("rows"));
                        ((ArrayNode)existing.path("issues")).addAll((ArrayNode)channel.path("issues"));
                        existing.put("errors",existing.path("errors").asInt()+channel.path("errors").asInt());
                        existing.put("quoteReady",existing.path("quoteReady").asBoolean()&&channel.path("quoteReady").asBoolean());
                    } else channels.put(key,channel);
                }
                report.put("status",errors>0?"blocked":"parsed").put("priceRows",rows).put("channels",parsed.size()).put("errors",errors);
            }
        } catch(AppException e){throw e;} catch(Exception e){throw AppException.unprocessable("物流文件无法解析："+e.getClass().getSimpleName());}
        var items=result.putArray("channels");
        for(var c:channels.values()) {
            validateMergedSheets(c);
            LogisticsReadiness.apply(c,mapper);
            c.put("contentHash",businessHash((ArrayNode)c.path("rows")));items.add(c);
        }
        return result;
    }

    private void validateMergedSheets(ObjectNode channel) {
        var rows=new ArrayList<JsonNode>();channel.path("rows").forEach(rows::add);
        for(int i=0;i<rows.size();i++)for(int j=i+1;j<rows.size();j++) {
            var a=rows.get(i);var b=rows.get(j);
            if(a.path("sourceSheet").equals(b.path("sourceSheet")))continue;
            boolean sameScope=true;
            for(var field:List.of("countryCode","areaName","zoneName","originRegion"))if(!a.path(field).equals(b.path(field)))sameScope=false;
            if(!sameScope)continue;
            double from=Math.max(a.path("weightFromKg").asDouble(),b.path("weightFromKg").asDouble());
            double to=Math.min(a.path("weightToKg").asDouble(),b.path("weightToKg").asDouble());
            if(from<to || (from==to && includes(a,from) && includes(b,from))) {
                issue(channel,b.path("sourceRow").asInt(),"跨表重量段","同一渠道不同工作表的重量档位重叠，必须核对后才能覆盖价格","error");
                channel.put("errors",channel.path("errors").asInt()+1).put("quoteReady",false);
            }
        }
    }
    private static boolean includes(JsonNode row,double value) {
        return (value>row.path("weightFromKg").asDouble()||row.path("weightFromInclusive").asBoolean())
                &&(value<row.path("weightToKg").asDouble()||row.path("weightToInclusive").asBoolean());
    }

    private void parseStandard(Source source,int header,String fallback,String file,Map<String,ObjectNode> channels) {
        for(int c=0;c<LogisticsWorkbookService.HEADERS.size();c++)if(!source.text(header,c).equals(LogisticsWorkbookService.HEADERS.get(c)))throw AppException.unprocessable("标准模板列头不完整或顺序错误："+source.sheet.getSheetName());
        var headers=new HashMap<String,Integer>();
        for(int c=0;c<100;c++) headers.put(source.text(header,c),c);
        for(int r=header+1;r<=source.sheet.getLastRowNum();r++) {
            if(source.rowEmpty(r))continue;
            var name=value(source,r,headers,"渠道名称"); var prov=value(source,r,headers,"物流商");
            var effectiveProvider=prov.isBlank()?fallback:prov;
            var sourceOrigin=value(source,r,headers,"发货区域");
            if(excludeSouthChinaPrice(effectiveProvider,sourceOrigin)){source.parsedRows.add(r);continue;}
            var target=channel(effectiveProvider,name.isBlank()?source.sheet.getSheetName():name,defaultText(value(source,r,headers,"货物属性"),"普货"),channels);
            if(target.path("providerName").asText().isBlank())issue(target,r+1,"物流商","标准表需填写物流商","error");
            var row=mapper.createObjectNode();
            for(int c=0;c<LogisticsWorkbookService.KEYS.length;c++) {
                var key=LogisticsWorkbookService.KEYS[c]; var text=source.text(r,c);
                if(Set.of(0,1,4,5,28,32,33,34,35,36).contains(c)) row.put(key,text);
                else if(Set.of(29,30,31,37).contains(c))row.put(key,flag(text));
                else numeric(row,key,source,r,c,target,true);
            }
            row.put("weightFromInclusive",flag(value(source,r,headers,"起点包含")));
            row.put("weightToInclusive",!headers.containsKey("终点包含")||flag(value(source,r,headers,"终点包含")));
            row.put("currency",defaultText(value(source,r,headers,"币种"),"CNY"));
            row.put("pricingModel",defaultText(value(source,r,headers,"计费方式"),row.path("intervalPrice").asDouble()>0?"interval":row.path("firstWeightPrice").asDouble()>0?"first-next":"per-kg"));
            applyOriginPolicy(row,effectiveProvider,sourceOrigin); row.put("notes",value(source,r,headers,"规则备注"));
            row.put("sourceProductCode",defaultText(value(source,r,headers,"原产品代码"),value(source,r,headers,"产品代码")));
            row.put("sourceCode",row.path("sourceProductCode").asText()).put("sourceFeeLabel","挂号费");
            if(row.path("etaMinDays").asInt()>0&&row.path("etaMaxDays").asInt()>=row.path("etaMinDays").asInt())row.put("etaSource","source-row");
            if(headers.containsKey("计费进位KG"))numeric(row,"billingStepKg",source,r,headers.get("计费进位KG"),target,true);
            row.put("pendingReason",value(source,r,headers,"待适配原因"));
            if(headers.containsKey("干线费每KG"))numeric(row,"linehaulPerKg",source,r,headers.get("干线费每KG"),target,true);
            target.put("logisticsAttribute",defaultText(value(source,r,headers,"货物属性"),"普货"));
            add(target,row,source,r,file);
        }
    }

    private boolean parseTable(Source source,String provider,String file,Map<String,ObjectNode> channels) {
        Columns columns=null; boolean recognized=false; String section=source.sheet.getSheetName().trim();
        var yanwenEta=provider.equals("燕文")?yanwenEtaExtractor(source):null;
        int auxiliary=-1;boolean example=false;
        var allNotes=new LinkedHashSet<String>();
        for(int r=0;r<=source.sheet.getLastRowNum();r++) {
            var rowText=String.join("|",source.rowTexts(r));
            if(rowText.contains("试算重量")&&rowText.contains("试算运费")){example=true;columns=null;continue;}
            boolean auxiliaryTitle=source.rowTexts(r).stream().anyMatch(t->t.length()<80&&t.contains("重派费用表"));
            if(auxiliaryTitle||(rowText.length()<160&&rowText.contains("重量")&&rowText.matches("(?s).*(重派费|附加费).*"))) {
                if(!rowText.matches("(?s).*(运费/kg|运费/KG|结算运费|首重).*")){auxiliary=r;example=false;columns=null;continue;}
            }
            if(provider.equals("极通环球")) {
                int end=parseHorizontalZones(source,r,provider,file,channels);
                if(end>=r){r=end;columns=null;recognized=true;continue;}
            }
            var header=detect(source,r);
            if(header!=null && (header.country>=0||provider.equals("容鼎"))){columns=header;recognized=true;auxiliary=-1;example=false;continue;}
            if(columns==null)for(var text:source.rowTexts(r))if(text.length()>20)allNotes.add(text);
            if(example){if(source.rowEmpty(r))example=false;else source.exampleRows.add(r);continue;}
            if(auxiliary>=0){source.auxiliaryRows.put(r,auxiliary);continue;}
            if(provider.equals("顺丰") && source.text(r,1).matches(".*[①②③④].*专线.*"))section=source.text(r,1).replaceAll("^[①②③④\\s]+","").trim();
            if(columns==null) continue;
            var weight=columns.from>=0?source.numberText(r,columns.from)+"-"+source.numberText(r,columns.to):source.text(r,columns.weight);
            if(!looksRange(weight)) {
                for(var text:source.rowTexts(r)) if(text.length()>20)allNotes.add(text);
                continue;
            }
            var countryRaw=columns.country>=0?source.text(r,columns.country):provider.equals("容鼎")?"美国":"";
            var name=columns.channel>=0?source.text(r,columns.channel):section;
            if(provider.equals("云速递") && section.contains("美国商派"))name=section+"-"+countryRaw;
            if(name.isBlank())name=section;
            var sourceOrigin=columns.origin>=0?source.text(r,columns.origin):"";
            if(excludeSouthChinaPrice(provider,sourceOrigin)){source.parsedRows.add(r);continue;}
            var target=channel(provider,name,channels);
            var row=mapper.createObjectNode().put("currency","CNY").put("pricingModel",columns.firstPrice>=0?"first-next":"per-kg");
            var rawCode=columns.countryCode>=0?source.text(r,columns.countryCode):countryCode(countryRaw);
            String sourceContinent=columns.continent>=0?source.text(r,columns.continent):"";
            String normalizedCode=rawCode.replaceFirst("^([A-Z]{2})-[1-9][0-9]*$","$1");
            if(!Arrays.asList(Locale.getISOCountries()).contains(normalizedCode))normalizedCode=countryCode(countryRaw);
            row.put("areaName",countryName(countryRaw)); row.put("countryCode",normalizedCode).put("sourceCountryCode",rawCode);
            row.put("sourceCountry",countryRaw).put("sourceCode",columns.code>=0?source.text(r,columns.code):"");
            row.put("sourceProductCode",row.path("sourceCode").asText());
            applyOriginPolicy(row,provider,sourceOrigin);
            String zoneName=columns.zone>=0?defaultText(source.text(r,columns.zone),zone(countryRaw)):zone(countryRaw);
            if(rawCode.matches("[A-Z]{2}-[1-9][0-9]*"))zoneName=rawCode.substring(3)+"区";
            var embeddedZone=Pattern.compile("^([1-9一二三四五六七八九]区)[（(](.*)[)）]$").matcher(clean(weight));
            String parseWeight=weight;
            if(embeddedZone.matches()){zoneName=embeddedZone.group(1);parseWeight=embeddedZone.group(2);}
            if(provider.equals("万邦")||provider.equals("云速递")) {
                for(int c=0;c<source.width(r);c++) {
                    var label=source.text(r,c);var found=Pattern.compile("([1-9一二三四五六七八九]区(?:[.、,，][1-9一二三四五六七八九]区)*)").matcher(label);
                    if(found.find() && (label.contains("区报价")||clean(label).matches("[1-9一二三四五六七八九]区(?:[.、,，][1-9一二三四五六七八九]区)*"))) {
                        zoneName=found.group(1);row.put("sourceZoneLabel",label).put("sourceZoneCell",source.address(r,c));
                    }
                    if(provider.equals("万邦")) {
                        var minimum=Pattern.compile("(?i)([0-9.]+)G起重").matcher(label);
                        if(minimum.find())row.put("minChargeWeightKg",Double.parseDouble(minimum.group(1))/1000);
                    }
                }
            }
            row.put("zoneName",normalizeZone(zoneName));
            if(provider.equals("顺丰")&&section.equals("国际电商专递-CD")&&countryCode(countryRaw).equals("NZ")&&clean(parseWeight).equalsIgnoreCase("1,001-5KG")) {
                parseWeight="1.001-5KG";row.put("normalizationNote","用户确认：新西兰1,001-5KG表示1.001-5KG");
            }
            if(parseWeight.contains("(")||parseWeight.contains("（"))pending(row,"重量范围附带首续重条件，需核对完整规则");
            try { var range=parseRange(parseWeight.replaceAll("[（(].*$","")); row.put("weightFromKg",range.from).put("weightToKg",range.to).put("weightFromInclusive",range.includeFrom).put("weightToInclusive",range.includeTo); }
            catch(IllegalArgumentException e){issue(target,r+1,"重量段",e.getMessage(),"error");continue;}
            row.put("sourceWeightRange",weight);
            if(columns.firstPrice>=0) {
                numeric(row,"firstWeightPrice",source,r,columns.firstPrice,target,false);
                numeric(row,"nextWeightPrice",source,r,columns.nextPrice,target,false);
                row.put("firstWeightKg",columns.firstKg);row.put("nextWeightKg",columns.nextKg);
                row.put("pricePerKg",0).put("registrationFee",0);
            } else {
                numeric(row,"pricePerKg",source,r,columns.rate,target,false);
                numeric(row,"registrationFee",source,r,columns.fee,target,false);
                row.put("sourceFeeLabel",columns.fee>=0?source.text(columns.feeHeaderRow,columns.fee):"");
            }
            if(columns.minimum>=0)numeric(row,"minChargeWeightKg",source,r,columns.minimum,target,true);
            if(columns.step>=0)numeric(row,"billingStepKg",source,r,columns.step,target,true);
            if(columns.linehaul>=0) {
                numeric(row,"linehaulPerKg",source,r,columns.linehaul,target,true);
                if(row.path("linehaulPerKg").asDouble()>0)pending(row,"干线费需要明确计费叠加规则");
            }
            if(columns.eta>=0) {
                var rawEta=source.text(r,columns.eta);
                if(provider.equals("燕文")&&!rawEta.isBlank()) {
                    var eta=parseEta(rawEta,source.address(r,columns.eta));
                    if(eta==null)issue(target,r+1,"参考时效","燕文价格行时效必须是明确的起止天数："+rawEta,"error");
                    else applyEta(row,eta,"row");
                } else {
                    var eta=numbers(rawEta);
                    if(!eta.isEmpty())row.put("etaMinDays",eta.getFirst());if(eta.size()>1)row.put("etaMaxDays",eta.get(1));
                    if(eta.size()>1)row.put("etaSource","source-row").put("sourceEtaCell",source.address(r,columns.eta)).put("sourceEtaText",rawEta);
                }
            }
            if(yanwenEta!=null)yanwenEta.apply(row,target,r+1,sourceContinent);
            var notes=new LinkedHashSet<String>();
            if(provider.equals("极通环球")){notes.add(columns.code>=0?source.text(r,columns.code):"");pending(row,"同表含多个下单产品及附加操作费，价格仅供管理，需人工确认适用产品");}
            for(int c:columns.notes) {var note=source.text(r,c);if(!note.isBlank()&&!note.equals("/"))notes.add(note);}
            row.put("notes",String.join("\n",notes));
            for(int c=0;c<source.width(r);c++)if(source.text(r,c).matches("(?s).*(暂时关停|暂停服务|暂停收寄|停止收寄).*")) {
                pending(row,"原表标记暂停服务，禁止自动报价");row.put("notes",row.path("notes").asText()+"\n[服务状态]"+source.text(r,c));
            }
            add(target,row,source,r,file);
        }
        for(var c:channels.values()) {
            c.put("sourceNotes",String.join("\n",allNotes));
            // Unknown conditional pricing must not silently become zero surcharges.
            var important=String.join("\n",allNotes);
            if(important.matches("(?s).*(免泡|半泡|倍.*[泡重]|干线费|附加费|进位|起重|最低|起收|计费|体积|材积|燃油).*")) {
                var businessNotes=allNotes.stream().filter(n->n.matches("(?s).*(免泡|半泡|倍.*[泡重]|干线费|附加费|进位|起重|最低|起收|计费|体积|材积|燃油).*"))
                        .sorted().toList();
                for(var row:c.path("rows")) {
                    pending((ObjectNode)row,"表尾计费/适用规则需适配核对");
                    ((ObjectNode)row).put("notes",row.path("notes").asText()+"\n[表级规则]\n"+String.join("\n",businessNotes));
                }
            }
            var reviewNotes=allNotes.stream().filter(n->n.matches("(?s).*(邮编|尺寸|申报|关税|税费|备注|说明).*"))
                    .sorted().toList();
            if(!reviewNotes.isEmpty())for(var row:c.path("rows"))warn((ObjectNode)row,"原表普通尺寸、邮编或说明需要人工复核");
        }
        return recognized;
    }
    private boolean parseMatrix(Source source,String provider,String file,Map<String,ObjectNode> channels) {
        int header=-1;
        for(int r=0;r<=source.sheet.getLastRowNum();r++)if(source.text(r,1).contains("运费")&&source.text(r,2).contains("费")){header=r;break;}
        if(header<0)return false;
        for(int c=1;c<source.width(header);c+=2) {
            if(!source.text(header,c).contains("运费"))continue;
            String name="";for(int h=header-1;h>=0;h--){var label=source.text(h,c);if(!label.isBlank()){name=label;break;}}
            if(name.isBlank())name=source.sheet.getSheetName()+"-"+CellReference.convertNumToColString(c);
            var target=channel(provider,name,channels);
            for(int r=header+1;r<=source.sheet.getLastRowNum();r++) {
                var text=source.text(r,0);if(!looksRange(text))continue;
                var row=mapper.createObjectNode().put("areaName",source.sheet.getSheetName().contains("加拿大")?"加拿大":"美国")
                        .put("countryCode",source.sheet.getSheetName().contains("加拿大")?"CA":"US").put("currency","CNY").put("pricingModel","per-kg");
                try{var range=parseRange(text);row.put("weightFromKg",range.from).put("weightToKg",range.to).put("weightFromInclusive",range.includeFrom).put("weightToInclusive",range.includeTo);}
                catch(IllegalArgumentException e){issue(target,r+1,"重量段",e.getMessage(),"error");continue;}
                numeric(row,"pricePerKg",source,r,c,target,false);numeric(row,"registrationFee",source,r,c+1,target,false);
                row.put("sourceFeeLabel",source.text(header,c+1));
                pending(row,"横向渠道表的收寄条件和表尾附加规则待核对");add(target,row,source,r,file);
            }
        }
        return true;
    }

    /** A country block can contain horizontal zone headers with a rate/fee pair per zone. */
    private int parseHorizontalZones(Source source,int header,String provider,String file,Map<String,ObjectNode> channels) {
        int weight=-1,country=-1;var zones=new LinkedHashMap<Integer,String>();
        for(int c=0;c<source.width(header);c++) {
            String text=clean(source.text(header,c));
            if(text.equals("重量区间")||text.equals("重量段"))weight=c;
            if(!countryCode(text).isBlank()&&countryName(text).equals(text))country=c;
            if(text.matches("[1-9一二三四五六七八九]区")&&source.address(header,c).equals(new CellReference(header,c).formatAsString()))zones.put(c,normalizeZone(text));
        }
        if(weight<0||country<0||zones.size()<2)return -1;
        int end=source.mergeEndRow(header,country);
        if(end<=header+1)return -1;
        var target=channel(provider,source.sheet.getSheetName().trim(),channels);
        for(var zone:zones.entrySet()) {
            int rate=zone.getKey(),fee=rate+1;
            if(!source.text(header+1,rate).contains("运费")||!source.text(header+1,fee).contains("挂号费")) {
                issue(target,header+2,"分区表头","横向分区必须有各自运费和挂号费列","error");return end;
            }
            for(int r=header+2;r<=end;r++) {
                String rawWeight=source.text(r,weight);if(source.rowEmpty(r))continue;
                var row=mapper.createObjectNode().put("areaName",countryName(source.text(header,country))).put("countryCode",countryCode(source.text(header,country)))
                        .put("zoneName",zone.getValue()).put("currency","CNY").put("pricingModel","per-kg").put("sourceWeightRange",rawWeight)
                        .put("sourceZoneLabel",source.text(header,rate)).put("sourceZoneCell",source.address(header,rate));
                try{var range=parseRange(rawWeight);row.put("weightFromKg",range.from).put("weightToKg",range.to).put("weightFromInclusive",range.includeFrom).put("weightToInclusive",range.includeTo);}
                catch(IllegalArgumentException e){issue(target,r+1,"重量段",e.getMessage(),"error");continue;}
                numeric(row,"pricePerKg",source,r,rate,target,false);numeric(row,"registrationFee",source,r,fee,target,false);
                row.put("sourceFeeLabel",source.text(header+1,fee));
                row.put("notes",String.join("\n",new LinkedHashSet<>(source.resolvedTexts(header))));
                pending(row,"分区、不同下单产品及附加操作费需核对，暂不开放自动报价");
                add(target,row,source,r,file);
            }
        }
        return end;
    }

    private Columns detect(Source source,int r) {
        var c=new Columns();c.feeHeaderRow=r;int last=source.sheet.getRow(r)==null?0:source.sheet.getRow(r).getLastCellNum();
        for(int col=0;col<Math.min(last,80);col++) {
            String t=clean(source.text(r,col)); String lower=t.toLowerCase(Locale.ROOT);
            if(t.matches("国家|国家/地区|国家名称|通达国家|服务国家"))c.country=col;
            if(t.equals("CountryCode")||t.equals("Code"))c.countryCode=col;
            if(t.equals("大洲"))c.continent=col;
            if(t.contains("产品名称")||t.equals("渠道名称"))c.channel=col;
            if(t.contains("产品代码")||t.equals("渠道代码"))c.code=col;
            if(t.contains("重量段始"))c.from=col;
            else if(t.contains("重量段终"))c.to=col;
            else if(t.contains("最小计费")||t.contains("最低计费"))c.minimum=col;
            else if(t.contains("进位"))c.step=col;
            else if(t.contains("重量段")||t.contains("重量区间")||t.startsWith("重量(")||t.startsWith("重量/")||t.contains("计费重量限制"))c.weight=col;
            if((t.contains("运费")||t.contains("公斤重"))&&(lower.contains("kg")||t.equals("运费单价")))c.rate=col;
            if(t.equals("结算运费")||t.equals("SF折后"))c.settlement=col;
            if((t.contains("操作费")||t.contains("处理费")||t.contains("挂号费")||t.contains("每票费"))&&!t.contains("附加费"))c.fee=col;
            if(t.contains("首重")&&!t.contains("续重")){c.firstPrice=col;c.firstKg=firstNumber(t,0.5);}
            if(t.contains("续重")){c.nextPrice=col;c.nextKg=firstNumber(t,0.5);}
            if(t.contains("干线费"))c.linehaul=col;
            if(t.equals("报价区域")||t.equals("起运仓库"))c.origin=col;
            if(t.equals("分区"))c.zone=col;
            if(t.contains("时效"))c.eta=col;
            if(t.matches(".*(备注|说明|尺寸|附加费|服务费).*"))c.notes.add(col);
        }
        if(c.settlement>=0){if(c.firstPrice>=0)c.firstPrice=c.settlement;else c.rate=c.settlement;}
        // Two-level 4PX headers place fee/rate on the row below the country/weight header.
        if(c.weight>=0 && c.country>=0 && c.rate<0 && source.text(r+1,5).contains("运费")) {c.rate=5;c.fee=6;c.feeHeaderRow=r+1;}
        // Rongding has a title-defined destination rather than a country column.
        return (c.weight>=0 || (c.from>=0&&c.to>=0)) && (c.rate>=0 || c.firstPrice>=0)?c:null;
    }
    private void add(ObjectNode target,ObjectNode row,Source source,int r,String file) {
        source.parsedRows.add(r);
        row.put("sourceFile",file).put("sourceSheet",source.sheet.getSheetName()).put("sourceRow",r+1);
        row.set("rawValues",source.rawRow(r));
        row.put("quoteReady",row.path("pendingReason").asText().isBlank());
        ((ArrayNode)target.path("rows")).add(row);
    }
    private YanwenEtaExtractor yanwenEtaExtractor(Source source) {
        var index=new YanwenEtaExtractor();
        for(int header=0;header<=source.sheet.getLastRowNum();header++) {
            int code=-1,eta=-1;
            for(int c=0;c<source.width(header);c++) {
                var label=clean(source.text(header,c));
                if(label.equalsIgnoreCase("CountryCode"))code=c;
                if(label.contains("参考时效"))eta=c;
            }
            if(code<0||eta<0)continue;
            for(int r=header+1;r<=source.sheet.getLastRowNum();r++) {
                if(isPriceHeader(source,r))break;
                var rawCode=clean(source.text(r,code)).toUpperCase(Locale.ROOT);
                var rawEta=source.text(r,eta);
                if(!rawCode.matches("[A-Z]{2}(?:-[0-9]+)?")||rawEta.isBlank())continue;
                var parsed=parseEta(rawEta,source.address(r,eta));
                if(parsed==null)index.invalidCountries.add(rawCode);else index.put(index.countries,index.conflictingCountries,rawCode,parsed);
            }
        }
        if(source.sheet.getSheetName().trim().equals("中邮上海线下E邮宝"))for(int r=0;r<=source.sheet.getLastRowNum();r++)for(int c=0;c<source.width(r);c++) {
            var text=source.text(r,c);
            for(var continent:List.of("亚洲","欧洲","南美洲","北美洲","大洋洲","非洲"))if(clean(text).startsWith(continent+"：")||clean(text).startsWith(continent+":")) {
                var parsed=parseEta(text,source.address(r,c));
                if(parsed==null)index.invalidContinents.add(continent);else index.put(index.continents,index.conflictingContinents,continent,parsed);
            }
        }
        return index;
    }
    private boolean isPriceHeader(Source source,int row) {
        var labels=source.rowTexts(row).stream().map(LogisticsSourceParser::clean).filter(v->v.length()<40).toList();
        boolean weight=labels.stream().anyMatch(v->v.contains("重量段")||v.contains("重量区间")||v.contains("计费重量限制"));
        boolean price=labels.stream().anyMatch(v->(v.contains("运费")||v.contains("公斤重"))&&(v.toUpperCase(Locale.ROOT).contains("KG")||v.equals("运费单价")));
        return weight&&price;
    }
    private EtaReference parseEta(String text,String cell) {
        var matcher=ETA_RANGE.matcher(text);EtaReference found=null;
        while(matcher.find()) {
            int min=Integer.parseInt(matcher.group(1)),max=Integer.parseInt(matcher.group(2));
            if(min<=0||max<min)return null;
            var next=new EtaReference(min,max,text,cell);
            if(found!=null&&(found.min!=next.min||found.max!=next.max))return null;
            found=next;
        }
        return found;
    }
    private static void applyEta(ObjectNode row,EtaReference eta,String scope) {
        row.put("etaMinDays",eta.min).put("etaMaxDays",eta.max).put("etaSource",scope.equals("row")?"source-row":"reference-"+scope)
                .put("sourceEtaScope",scope).put("sourceEtaCell",eta.cell).put("sourceEtaText",eta.text);
    }
    private record EtaReference(int min,int max,String text,String cell){}
    private final class YanwenEtaExtractor {
        final Map<String,EtaReference> countries=new HashMap<>(),continents=new HashMap<>();
        final Set<String> conflictingCountries=new HashSet<>(),conflictingContinents=new HashSet<>(),invalidCountries=new HashSet<>(),invalidContinents=new HashSet<>(),reported=new HashSet<>();
        void put(Map<String,EtaReference> values,Set<String> conflicts,String key,EtaReference value) {
            if(conflicts.contains(key))return;var previous=values.get(key);
            if(previous==null)values.put(key,value);else if(previous.min!=value.min||previous.max!=value.max){values.remove(key);conflicts.add(key);}
        }
        void apply(ObjectNode row,ObjectNode channel,int sourceRow,String sourceContinent) {
            int existingMin=row.path("etaMinDays").asInt(),existingMax=row.path("etaMaxDays").asInt();
            if(existingMin>0&&existingMax>=existingMin)return;
            if(existingMin>0||existingMax>0){issue(channel,sourceRow,"参考时效","燕文价格行时效不完整，禁止使用参考表覆盖","error");return;}
            var raw=clean(row.path("sourceCountryCode").asText()).toUpperCase(Locale.ROOT);
            var normalized=clean(row.path("countryCode").asText()).toUpperCase(Locale.ROOT);
            var keys=new LinkedHashSet<String>();if(!raw.isBlank()){keys.add(raw);keys.add(raw.replaceFirst("-[0-9]+$",""));}if(!normalized.isBlank())keys.add(normalized);
            for(var key:keys) {
                if(conflictingCountries.contains(key)){report(channel,sourceRow,"country:"+key,"同一国家简码存在相互冲突的参考时效："+key);return;}
                if(invalidCountries.contains(key)){report(channel,sourceRow,"country-invalid:"+key,"国家参考时效不是明确的起止天数："+key);return;}
                var value=countries.get(key);if(value!=null){applyEta(row,value,"country");return;}
            }
            var continent=sourceContinent.trim();if(continent.equals("亚洲（中东）")||continent.equals("亚洲(中东)"))continent="亚洲";
            if(!continent.isBlank()) {
                if(conflictingContinents.contains(continent)){report(channel,sourceRow,"continent:"+continent,"同一大洲存在相互冲突的参考时效："+continent);return;}
                if(invalidContinents.contains(continent)){report(channel,sourceRow,"continent-invalid:"+continent,"大洲参考时效不是明确的起止天数："+continent);return;}
                var value=continents.get(continent);if(value!=null)applyEta(row,value,"continent");
            }
        }
        void report(ObjectNode channel,int row,String key,String message){if(reported.add(key))issue(channel,row,"参考时效",message,"error");}
    }
    private void finish(ObjectNode channel,String provider) {
        if(!channel.has("templateStatus"))channel.put("templateStatus","known");
        var rows=(ArrayNode)channel.path("rows"); var previous=new HashMap<String,ObjectNode>(); var seen=new HashSet<String>();
        var sorted=new ArrayList<JsonNode>();rows.forEach(sorted::add);
        sorted.sort(Comparator.comparing(LogisticsSourceParser::scope).thenComparingDouble(r->r.path("weightFromKg").asDouble()).thenComparingDouble(r->r.path("weightToKg").asDouble()));
        rows.removeAll();sorted.forEach(rows::add);
        for(var item:rows) {
            var row=(ObjectNode)item;
            for(var field:List.of("notes","originRegion","pendingReason","blockingReason","reviewWarning","sourceProductCode","sourceFeeLabel","sourceOriginRegion","etaSource"))if(!row.has(field))row.put(field,"");
            // Standard cells are trimmed on import; normalize outer whitespace before hashing too.
            row.put("notes",row.path("notes").asText().trim());
            for(var field:List.of("billingStepKg","linehaulPerKg"))if(!row.has(field))row.put(field,0);
            for(int column=0;column<LogisticsWorkbookService.KEYS.length;column++) {
                var field=LogisticsWorkbookService.KEYS[column];
                if(row.has(field))continue;
                if(Set.of(0,1,4,5,28,32,33,34,35,36).contains(column))row.put(field,"");
                else if(Set.of(29,30,31,37).contains(column))row.put(field,column==29);
                else row.put(field,0);
            }
            var group=scope(row);
            var prev=previous.get(group); double from=row.path("weightFromKg").asDouble(),to=row.path("weightToKg").asDouble();
            if(prev!=null) {
                var end=prev.path("weightToKg").asDouble();
                if(provider.equals("云速递")&&row.path("countryCode").asText().equals("US")&&row.path("zoneName").asText().isBlank()) {
                    String channelName=channel.path("channelName").asText();
                    boolean confirmed=(channelName.equals("全球专线带电")&&from==2&&to==30&&end==5)
                            ||(channelName.equals("全球专线敏感")&&from==0.2&&to==0.45&&end==0.3);
                    if(confirmed){row.put("weightFromKg",end).put("weightFromInclusive",false).put("normalizationNote","用户确认：修正美国重叠档位下限，保持左开右闭");from=end;}
                }
                if(Math.abs(from-end)<1e-8 && row.path("weightFromInclusive").asBoolean())row.put("weightFromInclusive",false);
                if((provider.equals("花海")||(provider.equals("顺丰")&&channel.path("channelName").asText().equals("国际电商专递-CD"))) && Math.abs(from-end-0.01)<1e-8) {
                    row.put("weightFromKg",BigDecimal.valueOf(end).add(new BigDecimal("0.001"))).put("weightFromInclusive",true)
                            .put("normalizationNote","按用户确认：上一档上限+1g，原始范围保留");from=end+0.001;
                }
                if(from<end-1e-8)issue(channel,row.path("sourceRow").asInt(),"重量段","同一国家/分区/发货区域存在重叠档位","error");
                if(from>end+0.00100001)issue(channel,row.path("sourceRow").asInt(),"重量段","相邻档位存在超过1g的缺口，需要确认","error");
            }
            previous.put(group,row);
            if(!Arrays.asList(Locale.getISOCountries()).contains(row.path("countryCode").asText())||row.path("areaName").asText().isBlank())issue(channel,row.path("sourceRow").asInt(),"国家","国家不能准确识别","error");
            if(to<=from)issue(channel,row.path("sourceRow").asInt(),"重量段","重量上下限不合法","error");
            if(!(row.path("pricePerKg").asDouble()>0||row.path("firstWeightPrice").asDouble()>0||row.path("intervalPrice").asDouble()>0))issue(channel,row.path("sourceRow").asInt(),"价格","缺少有效计费价格","error");
            var model=row.path("pricingModel").asText();
            if(!Set.of("per-kg","first-next").contains(model))pending(row,model.equals("interval")?"区间价计费方式暂不支持":"计费方式未适配");
            int primaryPrices=(row.path("pricePerKg").asDouble()>0?1:0)+(row.path("firstWeightPrice").asDouble()>0?1:0)+(row.path("intervalPrice").asDouble()>0?1:0);
            if(primaryPrices>1)issue(channel,row.path("sourceRow").asInt(),"计费方式","多个基础计费价格同时非零，需要明确叠加或互斥规则","error");
            if(model.equals("first-next")&&(row.path("firstWeightKg").asDouble()<=0||row.path("firstWeightPrice").asDouble()<=0||(to>row.path("firstWeightKg").asDouble()&&row.path("nextWeightKg").asDouble()<=0)))
                issue(channel,row.path("sourceRow").asInt(),"首续重","首重及续重参数不完整","error");
            if(model.equals("per-kg")&&row.path("pricePerKg").asDouble()<=0)
                issue(channel,row.path("sourceRow").asInt(),"计费方式","计费方式与基础价格字段不一致","error");
            if(!row.path("currency").asText("CNY").equals("CNY"))pending(row,"非人民币计价需要币种适配");
            if(row.path("surcharge").asDouble()>0)pending(row,"附加费需要明确计费适用规则");
            if(row.path("fuelSurchargeRate").asDouble()>0)pending(row,"燃油附加费率尚未接入自动计费");
            if(row.path("billingStepKg").asDouble()>0 && !row.path("pricingModel").asText().equals("first-next"))pending(row,"普通计费进位规则需要适配");
            if(row.path("notes").asText().matches("(?s).*(免泡|半泡|倍|附加费|燃油|干线费|起重|最低|起收).*"))pending(row,"行级价格条件需适配核对");
            else if(!row.path("notes").asText().isBlank())warn(row,"原表普通尺寸、邮编或说明需要人工复核");
            if(row.path("linehaulPerKg").asDouble()>0)pending(row,"干线费需要明确计费叠加规则");
            for(var unsupported:List.of("minLengthCm","maxLengthCm","minWidthCm","maxWidthCm","minSideAreaCm2","maxSideAreaCm2"))if(row.path(unsupported).asDouble()>0)warn(row,"尺寸准入信息需要人工复核");
            var key=LogisticsDatasetService.hash(group+"|"+row.path("weightFromKg").asDouble()+"|"+to+"|"+row.path("weightFromInclusive").asBoolean()+"|"+row.path("weightToInclusive").asBoolean());row.put("rowKey",key);
            if(!seen.add(key))issue(channel,row.path("sourceRow").asInt(),"重量段","重复计费档位","error");
        }
        channel.put("parserVersion",VERSION);
        int errors=0;for(var issue:channel.path("issues"))if(issue.path("level").asText().equals("error"))errors++;
        if(rows.isEmpty()&&errors==0)issue(channel,0,"价格","未提取到价格行","error");
        channel.put("validRows",rows.size());
        LogisticsReadiness.apply(channel,mapper);
    }
    ObjectNode channel(String provider,String name,Map<String,ObjectNode> channels) {
        return channel(provider,name,attribute(name),channels);
    }
    ObjectNode channel(String provider,String name,String attribute,Map<String,ObjectNode> channels) {
        var key=LogisticsDatasetService.normalize(provider)+"|"+LogisticsDatasetService.normalize(name)+"|"+attribute;
        return channels.computeIfAbsent(key,k->{var c=mapper.createObjectNode().put("providerName",provider).put("channelName",name).put("logisticsAttribute",attribute);c.putArray("rows");c.putArray("issues");return c;});
    }
    static String identity(JsonNode channel){return LogisticsDatasetService.normalize(channel.path("providerName").asText())+"|"+LogisticsDatasetService.normalize(channel.path("channelName").asText())+"|"+channel.path("logisticsAttribute").asText();}
    public String businessHash(ArrayNode rows) {
        var normalized=new ArrayList<String>();
        for(var row:rows) {
            var fields=new TreeMap<String,JsonNode>();
            row.properties().forEach(e->{if(!e.getKey().startsWith("source")&&!Set.of("rawValues","normalizationNote","rowKey","routeKey","quoteReady","pendingReason","blockingReason","reviewWarning","etaSource").contains(e.getKey()))fields.put(e.getKey(),e.getValue().isNumber()?mapper.getNodeFactory().numberNode(e.getValue().decimalValue().stripTrailingZeros()):e.getValue());});
            normalized.add(mapper.writeValueAsString(fields));
        }
        Collections.sort(normalized);
        return LogisticsDatasetService.hash(mapper.writeValueAsString(normalized));
    }
    private void numeric(ObjectNode out,String key,Source source,int r,int c,ObjectNode channel,boolean blankZero) {
        String raw=c<0?"":source.text(r,c);
        if(raw.isBlank()&&blankZero){out.put(key,0);return;}
        try {
            Cell cell=source.cell(r,c);
            if(cell!=null && cell.getCellType()==CellType.FORMULA && (cell.getCellFormula().contains("[")||cell.getCachedFormulaResultType()!=CellType.NUMERIC))throw new NumberFormatException();
            if(cell!=null&&(cell.getCellType()==CellType.NUMERIC||cell.getCellType()==CellType.FORMULA))raw=Double.toString(cell.getNumericCellValue());
            var value=new BigDecimal(raw.replaceAll("(?i)人民币|RMB|CNY|KG|公斤|千克|[¥￥,\\s\\u00a0]", ""));
            if(value.signum()<0)throw new NumberFormatException();
            out.put(key,value.stripTrailingZeros());
        } catch(Exception e){issue(channel,r+1,key,"关键价格/重量不是可靠数字："+raw,"error");}
    }
    private void issue(ObjectNode channel,int row,String field,String message,String level){((ArrayNode)channel.path("issues")).addObject().put("row",row).put("field",field).put("message",message).put("level",level);}
    private static void pending(ObjectNode row,String reason){LogisticsReadiness.block(row,reason);}
    private static void warn(ObjectNode row,String reason){LogisticsReadiness.warn(row,reason);}
    private static boolean fourPx(String provider){return provider.equals("递四方")||provider.toLowerCase(Locale.ROOT).contains("4px");}
    private static boolean excludeSouthChinaPrice(String provider,String origin){return fourPx(provider)&&origin.contains("华南")&&!origin.contains("华东");}
    private static void applyOriginPolicy(ObjectNode row,String provider,String origin){
        if(fourPx(provider)&&origin.contains("华东")){
            row.put("sourceOriginRegion",origin).put("originRegion","");
            row.put("normalizationNote","按用户确认：递四方统一采用华东起运仓报价");
        } else row.put("sourceOriginRegion",origin).put("originRegion",origin);
    }
    private static String value(Source s,int r,Map<String,Integer> h,String key){return h.containsKey(key)?s.text(r,h.get(key)):"";}
    static String provider(String name){if(name.toLowerCase(Locale.ROOT).contains("4px"))return "递四方";for(var p:PROVIDERS)if(name.contains(p))return p;return "";}
    static String attribute(String name){if(name.matches(".*(化妆|彩妆).*"))return "非液体化妆品";if(name.contains("服装"))return "普货";if(name.matches(".*(电|特货).*"))return "带电";if(name.matches(".*(敏|特敏).*"))return "敏感货";return "普货";}
    static String clean(String value){return value.replace("（","(").replace("）",")").replaceAll("[\\s\\u00a0]+","");}
    static boolean flag(String text){return text.matches("(?i)是|true|1|yes");}
    static String defaultText(String value,String fallback){return value.isBlank()?fallback:value;}
    static List<Double> numbers(String text){var m=NUM.matcher(text);var values=new ArrayList<Double>();while(m.find())values.add(Double.parseDouble(m.group()));return values;}
    static double firstNumber(String text,double fallback){var nums=numbers(text);return nums.isEmpty()?fallback:nums.getFirst();}
    static boolean looksRange(String value){return clean(value).matches("(?i)^(?:[1-9一二三四五六七八九]区\\(.*|[0-9.,]+(?:KG|K|G|克)?[-—–~～<>≤≥＜＞=]+(?:W|重量)?[<≤=]*[0-9.]+.*|(?:W|重量)?(?:<=|<|≤|＜)[0-9.]+(?:KG|K|G|克|公斤|千克)?(?:\\(.*)?)$");}
    public record WeightRange(double from,double to,boolean includeFrom,boolean includeTo){}
    public static WeightRange parseRange(String raw) {
        var units=clean(raw).toUpperCase(Locale.ROOT).replace("千克","KG").replace("公斤","KG").replace("克","G");
        var endpoints=Pattern.compile("^([0-9.]+)(KG|G)?[-—–~～]([0-9.]+)(KG|G)?$").matcher(units);
        if(endpoints.matches()) {
            String left=endpoints.group(2),right=endpoints.group(4);if(left==null)left=right;if(right==null)right=left;
            double from=Double.parseDouble(endpoints.group(1))/("G".equals(left)?1000:1),to=Double.parseDouble(endpoints.group(3))/("G".equals(right)?1000:1);
            return new WeightRange(from,to,from>0,true);
        }
        var grams=Pattern.compile("^(?:W|重量)?(<=|<|≤|＜)([0-9.]+)G$").matcher(units);
        if(grams.matches())return new WeightRange(0,Double.parseDouble(grams.group(2))/1000,false,Set.of("<=","≤").contains(grams.group(1)));
        var value=units.replace("KG","").replace("＜","<").replace("＞",">").replace("≤","<=").replace("≥",">=").replace("—","-").replace("–","-").replace("～","-").replace("~","-");
        value=value.replaceAll("K$","");
        var upper=Pattern.compile("^(?:W|重量)?(<=|<)([0-9.]+)$").matcher(value);
        if(upper.matches())return new WeightRange(0,Double.parseDouble(upper.group(2)),false,upper.group(1).equals("<="));
        var match=RANGE.matcher(value);
        if(match.matches())return new WeightRange(Double.parseDouble(match.group(1)),Double.parseDouble(match.group(4)),match.group(2).equals("<="),match.group(3).equals("<="));
        if(value.matches("[0-9.]+-[0-9.]+")){var parts=value.split("-");double from=Double.parseDouble(parts[0]);return new WeightRange(from,Double.parseDouble(parts[1]),from>0,true);}
        throw new IllegalArgumentException("无法准确识别重量边界："+raw);
    }
    static String countryCode(String text){return COUNTRIES.entrySet().stream().filter(e->text.startsWith(e.getKey())).max(Comparator.comparingInt(e->e.getKey().length())).map(Map.Entry::getValue).orElse("");}
    static String countryName(String text){return COUNTRIES.keySet().stream().filter(text::startsWith).max(Comparator.comparingInt(String::length)).orElse(text);}
    static String zone(String text){var name=countryName(text);return text.equals(name)?"":text.substring(name.length()).trim();}
    private static String normalizeZone(String value){var v=clean(value).replaceAll("^[/（(]+|[）)]+$","");for(int i=0;i<9;i++)v=v.replace("一二三四五六七八九".substring(i,i+1)+"区",(i+1)+"区");return v.replaceAll("[.、，]","/");}
    private static String scope(JsonNode row){return row.path("countryCode").asText()+"|"+row.path("areaName").asText()+"|"+row.path("zoneName").asText()+"|"+row.path("originRegion").asText();}
    private static Map<String,String> countries(){var m=new HashMap<String,String>();for(var code:Locale.getISOCountries())m.put(new Locale.Builder().setRegion(code).build().getDisplayCountry(Locale.SIMPLIFIED_CHINESE),code);m.put("英国","GB");m.put("韩国","KR");m.put("捷克","CZ");m.put("中国台湾","TW");m.put("台湾","TW");m.put("中国香港","HK");m.put("香港","HK");m.put("俄罗斯","RU");m.put("阿联酋","AE");m.put("土库曼","TM");return m;}
    private static class Columns {
        int feeHeaderRow=-1,country=-1,countryCode=-1,continent=-1,channel=-1,code=-1,weight=-1,from=-1,to=-1,rate=-1,fee=-1,settlement=-1,minimum=-1,step=-1,origin=-1,zone=-1,eta=-1,linehaul=-1,firstPrice=-1,nextPrice=-1;
        double firstKg=0.5,nextKg=0.5;List<Integer> notes=new ArrayList<>();
    }
    private class Source {
        final Sheet sheet;final int nonempty;final DataFormatter formatter=new DataFormatter(Locale.ROOT);
        final Set<Integer> parsedRows=new HashSet<>();
        final Set<Integer> exampleRows=new HashSet<>();
        final Map<Integer,Integer> auxiliaryRows=new HashMap<>();
        final Map<Integer,List<org.apache.poi.ss.util.CellRangeAddress>> merges=new HashMap<>();
        Source(Sheet sheet){this.sheet=sheet;if(sheet.getLastRowNum()>100000)throw AppException.unprocessable("工作表超过100000行");formatter.setUseCachedValuesForFormulaCells(true);int count=0;for(var r:sheet)for(var c:r)if(!formatter.formatCellValue(c).isBlank())count++;nonempty=count;
            for(var range:sheet.getMergedRegions())for(int row=range.getFirstRow();row<=Math.min(range.getLastRow(),sheet.getLastRowNum());row++)merges.computeIfAbsent(row,k->new ArrayList<>()).add(range);
        }
        Cell cell(int r,int c){if(c<0)return null;for(var range:merges.getOrDefault(r,List.of()))if(range.isInRange(r,c)){r=range.getFirstRow();c=range.getFirstColumn();break;}var row=sheet.getRow(r);return row==null?null:row.getCell(c);}
        String numberText(int r,int c){var cell=cell(r,c);return cell!=null&&cell.getCellType()==CellType.NUMERIC?Double.toString(cell.getNumericCellValue()):text(r,c);}
        String text(int r,int c){var cell=cell(r,c);return cell==null?"":formatter.formatCellValue(cell).trim();}
        int width(int r){int width=sheet.getRow(r)==null?0:sheet.getRow(r).getLastCellNum();for(var range:merges.getOrDefault(r,List.of()))width=Math.max(width,range.getLastColumn()+1);return width;}
        String address(int r,int c){var cell=cell(r,c);return cell==null?new CellReference(r,c).formatAsString():cell.getAddress().formatAsString();}
        int mergeEndRow(int r,int c){for(var range:merges.getOrDefault(r,List.of()))if(range.isInRange(r,c))return range.getLastRow();return r;}
        List<String> resolvedTexts(int r){var values=new ArrayList<String>();for(int c=0;c<width(r);c++){var value=text(r,c);if(!value.isBlank())values.add(value);}return values;}
        boolean rowEmpty(int r){return rowTexts(r).stream().allMatch(String::isBlank);}
        List<String> rowTexts(int r){var row=sheet.getRow(r);if(row==null)return List.of();var values=new ArrayList<String>();for(var c:row)values.add(formatter.formatCellValue(c).trim());return values;}
        ObjectNode rawRow(int r){var result=mapper.createObjectNode();var row=sheet.getRow(r);if(row!=null)for(var c:row){var v=formatter.formatCellValue(c);if(!v.isBlank())result.put(c.getAddress().formatAsString(),v);}return result;}
        int auxiliaryHeader(int r){
            for(int h=r-1;h>=Math.max(0,r-15);h--){
                var text=String.join("|",rowTexts(h));
                if(text.contains("重量")&&text.matches("(?s).*(重派费|附加费|操作费).*")) {
                    if(text.matches("(?s).*(运费|首重|结算).*"))return -1;
                    return h;
                }
            }
            return -1;
        }
        ArrayNode raw(){var result=mapper.createArrayNode();for(var r:sheet){var row=rawRow(r.getRowNum());if(!row.isEmpty())result.add(row);}return result;}
    }
}
