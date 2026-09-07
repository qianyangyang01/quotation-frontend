package com.milano.quotation.logistics;

import com.milano.quotation.common.AppException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.util.*;

/** Compatibility endpoints use the same directory, durable batch and parser as the workspace. */
@Service
public class CompanyScopedImports {
    private final CompanyChannelService directory;private final LogisticsParserClient parser;private final LogisticsImportService imports;
    private final JdbcClient jdbc;private final ObjectMapper mapper;
    public CompanyScopedImports(CompanyChannelService directory,LogisticsParserClient parser,LogisticsImportService imports,JdbcClient jdbc,ObjectMapper mapper){this.directory=directory;this.parser=parser;this.imports=imports;this.jdbc=jdbc;this.mapper=mapper;}
    public boolean enabled(){return directory.state(false).path("enabled").asBoolean();}
    @Transactional
    public ObjectNode preview(UUID provider,List<MultipartFile> files){
        LogisticsImportService.validateFiles(files);directory.assertImport(directory.importDataset());
        var scope=new CompanyChannelScope(directory.scopeFor(provider,null));
        var result=mapper.createObjectNode().put("scopeRevision",scope.revision());var items=result.putArray("items");int index=0,filtered=0,blocking=0;
        for(var file:files){try{
            var parsed=parser.parse(file.getBytes(),file.getOriginalFilename(),scope);filtered+=parsed.path("filteredChannels").asInt();blocking+=parsed.path("ambiguousChannels").asInt();
            for(var value:parsed.path("channels")){
                var item=(ObjectNode)value.deepCopy();item.put("fileIndex",index).put("fileName",file.getOriginalFilename()).put("fileKey",index+":"+item.path("companyChannelId").asText())
                    .put("action","match").put("providerMatchStatus","matched").put("matchType","name").put("validRows",item.path("rows").size());
                if(item.path("errors").asInt()>0)blocking++;items.add(item);
            }
        }catch(Exception error){throw AppException.unprocessable("文件预检失败："+file.getOriginalFilename()+"，"+error.getMessage());}index++;}
        return result.put("count",items.size()).put("filteredChannels",filtered).put("blocking",blocking).put("selectable",Math.max(0,items.size()-blocking)).put("replaceDrafts",0);
    }
    @Transactional
    public ObjectNode upload(UUID provider,UUID channel,List<MultipartFile> files,String actor,String key,boolean replace){
        var dataset=directory.importDataset();
        if(channel!=null)directory.assertChannel(channel);
        var accepted=imports.uploadSelected(dataset,files,actor,key,replace,provider,channel);var id=UUID.fromString(accepted.path("id").asText());
        imports.process(id);var batch=imports.get(id);var result=mapper.createObjectNode().put("batchId",id.toString()).put("scopeRevision",batch.path("payload").path("scopeRevision").asLong());
        var items=result.putArray("items");for(var value:batch.path("payload").path("results")){
            var item=(ObjectNode)value.deepCopy();item.put("action",item.path("status").asText().equals("unchanged")?"duplicate":"updated");items.add(item);
        }
        result.put("count",items.size()).put("filteredChannels",batch.path("payload").path("filteredChannels").asInt());result.set("fileReports",batch.path("payload").path("fileReports"));
        if(channel!=null&&items.size()==1&&!items.get(0).path("versionId").asText().isBlank())return jdbc.sql("select payload::text from logistics_version where id=:id").param("id",UUID.fromString(items.get(0).path("versionId").asText())).query((rs,n)->(ObjectNode)mapper.readTree(rs.getString(1))).single();
        if(channel!=null)result.put("filtered",true).put("message","处理完成，文件未匹配该公司渠道，请查看导入批次报告");
        return result;
    }
}
