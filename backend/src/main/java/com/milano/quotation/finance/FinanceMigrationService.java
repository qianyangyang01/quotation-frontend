package com.milano.quotation.finance;

import com.milano.quotation.common.AppException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.List;

@Service
public class FinanceMigrationService {
    private static final List<String> KEYS=List.of("country-classification","channel-policies","customer-grades","exchange-rate","tax-settings");
    private final FinanceSettingRepository settings;
    public FinanceMigrationService(FinanceSettingRepository settings){this.settings=settings;}

    @Transactional
    public ObjectNode apply(String key,JsonNode value,boolean replace){
        if(!KEYS.contains(key))throw AppException.unprocessable("无法识别的财务设置："+key);if(value==null||value.isNull())throw AppException.unprocessable("财务设置不能为空："+key);
        var result=JsonNodeFactory.instance.objectNode().put("key",key);var existing=settings.findById(key);
        if(existing.isPresent()){result.set("before",existing.get().payload.deepCopy());if(existing.get().payload.equals(value)){result.put("changed",false);return result;}if(!replace&&!bootstrapEmpty(key,existing.get().payload))throw AppException.conflict("生产财务设置已存在且内容不同："+key);existing.get().payload=value.deepCopy();existing.get().updatedAt=Instant.now();settings.saveAndFlush(existing.get());}
        else{var row=FinanceSetting.create(key,value.deepCopy());settings.saveAndFlush(row);result.putNull("before");}
        result.put("changed",true);return result;
    }

    @Transactional public void rollback(JsonNode changes){for(var item:changes){if(!item.path("changed").asBoolean())continue;var key=item.path("key").asText();var before=item.path("before");if(before.isNull())settings.deleteById(key);else settings.findById(key).ifPresent(row->{row.payload=before.deepCopy();row.updatedAt=Instant.now();});}}
    private static boolean bootstrapEmpty(String key,JsonNode value){return switch(key){case"country-classification","channel-policies","customer-grades"->value.isArray()&&value.isEmpty();case"exchange-rate"->value.path("usdCny").isMissingNode()||value.path("usdCny").isNull();case"tax-settings"->(value.path("countries").isArray()&&value.path("countries").isEmpty())&&(value.path("providers").isArray()&&value.path("providers").isEmpty());default->false;};}
}
