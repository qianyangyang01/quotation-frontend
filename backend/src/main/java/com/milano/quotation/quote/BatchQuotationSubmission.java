package com.milano.quotation.quote;

import com.milano.quotation.common.AppException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.*;
import java.util.*;

/** Validate the merged preview, then produce ordinary, independently reviewable SKU snapshots. */
@Service
public class BatchQuotationSubmission {
    private final BatchSingleQuotation single;
    private final FobQuotation fob;
    public BatchQuotationSubmission(BatchSingleQuotation single, FobQuotation fob) { this.single=single; this.fob=fob; }

    public List<ObjectNode> prepare(JsonNode body) {
        if ("single".equals(body.path("kind").asText())) {
            var document=single.prepare(body.path("document"));
            var result=new ArrayList<ObjectNode>();
            for (var item:document.path("items")) result.add((ObjectNode)item.path("input").deepCopy());
            return result;
        }
        if (!"fob".equals(body.path("kind").asText()) || body.at("/fob/schemaVersion").asInt()!=2)
            throw AppException.unprocessable("批量报价类型无效");
        var request=JsonNodeFactory.instance.objectNode().put("quoteMode","fob");
        request.set("customerName",body.path("customerName")); request.set("fob",body.path("fob"));
        var prepared=fob.prepare(request).path("fob");
        var result=new ArrayList<ObjectNode>();
        for(var snapshot:splitFob(prepared)) {
            var child=JsonNodeFactory.instance.objectNode().put("quoteMode","fob");
            child.set("customerName",body.path("customerName")); child.set("fob",snapshot);
            result.add(fob.prepare(child));
        }
        return result;
    }

    /** Repeated SKU quantities belong to one schema-1 snapshot, never the legacy batch-review schema. */
    static List<ObjectNode> splitFob(JsonNode snapshot) {
        var groups=new LinkedHashMap<String,List<Integer>>();
        var items=snapshot.path("items");
        for(int i=0;i<items.size();i++) groups.computeIfAbsent(items.get(i).at("/product/sku").asText(),k->new ArrayList<>()).add(i);
        var result=new ArrayList<ObjectNode>();
        for(var group:groups.entrySet()) {
            var first=items.get(group.getValue().get(0));
            var child=(ObjectNode)first.deepCopy(); child.put("schemaVersion",1); child.remove("items");
            var sheet=(ObjectNode)snapshot.path("sheet").deepCopy(); sheet.remove("fobBatch");
            var rowKeys=new HashMap<String,String>(); var quantities=JsonNodeFactory.instance.arrayNode(); int rowIndex=0;
            for(int index:group.getValue()) {
                var item=items.get(index);
                if(!first.path("product").equals(item.path("product"))) throw AppException.unprocessable(group.getKey()+"：同一SKU不能混用不同采购来源");
                quantities.add(item.path("quantity"));
                for(var row:item.at("/sheet/rows")) rowKeys.put(index+":"+row.path("key").asText(),group.getValue().size()==1 ? row.path("key").asText() : group.getKey()+"-"+rowIndex++);
            }
            if(group.getValue().size()>1) child.set("quantities",quantities);
            var rows=sheet.putArray("rows");
            for(var row:snapshot.at("/sheet/rows")) {
                var key=rowKeys.get(row.path("key").asText()); if(key==null) continue;
                var copy=(ObjectNode)row.deepCopy(); copy.put("key",key).put("number",rows.size()+1); rows.add(copy);
            }
            child.set("sheet",sheet); result.add(child);
        }
        return result;
    }
}
