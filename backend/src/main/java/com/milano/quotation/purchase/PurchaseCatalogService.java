package com.milano.quotation.purchase;

import com.milano.quotation.common.PageResponse;
import com.milano.quotation.common.AppException;
import com.milano.quotation.fob.FobPurchaseRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.text.Normalizer;
import java.time.LocalDate;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.ss.util.CellRangeAddress;
import java.util.*;

/** Read-only procurement catalog. Quotation lookup and all writes retain their source-specific APIs. */
@Service
public class PurchaseCatalogService {
    private final JdbcClient jdbc;
    private final PurchaseProductRepository ordinary;
    private final PurchaseProductService products;
    private final FobPurchaseRepository fob;
    private final ObjectMapper mapper;
    public PurchaseCatalogService(JdbcClient jdbc, PurchaseProductRepository ordinary, PurchaseProductService products, FobPurchaseRepository fob, ObjectMapper mapper) {
        this.jdbc=jdbc; this.ordinary=ordinary; this.products=products; this.fob=fob; this.mapper=mapper;
    }
    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public PageResponse<JsonNode> page(String query,int page,int size,boolean includeFob,LocalDate dateFrom,LocalDate dateTo) {
        var selection=selection(query,includeFob,dateFrom,dateTo);
        String filter=selection.filter();
        // Only metadata is materialized. Fetch full payloads for the selected page in two batches.
        var selected=jdbc.sql("""
            with matches as materialized (
              select 'purchase' as kind,sku,updated_at from purchase_product where %s
              union all
              select 'fob' as kind,sku,updated_at from fob_purchase_product where :fob and %s
            ), selected as (
              select * from matches order by updated_at desc,sku,kind limit :limit offset :offset
            )
            select selected.kind,selected.sku,(select count(*) from matches) as total
            from (select 1) anchor left join selected on true order by selected.updated_at desc,selected.sku,selected.kind
            """.formatted(filter,filter)).params(selection.params()).param("limit",size).param("offset",(long)page*size)
                .query((rs,n)->new Match(rs.getString("kind"),rs.getString("sku"),rs.getLong("total"))).list();
        var values=new HashMap<String,JsonNode>();
        var ordinarySkus=selected.stream().filter(m->"purchase".equals(m.kind)).map(Match::sku).toList();
        if(!ordinarySkus.isEmpty()) for(var row:ordinary.findAllBySkuIn(ordinarySkus)) values.put("purchase:"+row.sku,products.view(row));
        var fobSkus=selected.stream().filter(m->"fob".equals(m.kind)).map(Match::sku).toList();
        if(!fobSkus.isEmpty()) for(var row:fob.findBySkuIn(fobSkus)) {
            var value=(ObjectNode)row.payload.deepCopy();
            value.put("sku",row.sku).put("dataSource","fob").put("version",row.version).put("updatedAt",row.updatedAt.toString());
            values.put("fob:"+row.sku,value);
        }
        long total=selected.getFirst().total;
        var items=selected.stream().filter(m->m.sku!=null).map(m->Objects.requireNonNull(values.get(m.kind+":"+m.sku))).toList();
        return new PageResponse<>(items,page,size,total,(int)Math.ceil((double)total/size));
    }

    private Selection selection(String query,boolean includeFob,LocalDate dateFrom,LocalDate dateTo) {
        if(dateFrom!=null&&dateTo!=null&&dateFrom.isAfter(dateTo))throw AppException.unprocessable("报价开始日期不能晚于结束日期");
        String cleaned=query==null?"":query.trim();
        String sku=Normalizer.normalize(cleaned,Normalizer.Form.NFKC).toUpperCase(Locale.ROOT).replaceAll("[\\s\\u200b]+","");
        // Keep exact SKU precedence, but check both stores before restricting the search.
        boolean exact=!sku.isEmpty() && sku.length()<=96 && jdbc.sql("""
            select exists(select 1 from purchase_product where sku=:sku)
                or (:fob and exists(select 1 from fob_purchase_product where sku=:sku))
            """).param("sku",sku).param("fob",includeFob).query(Boolean.class).single();
        String filter=exact?"sku=:sku":cleaned.isEmpty()?"true":"(lower(sku) like :pattern or lower(payload::text) like :pattern)";
        var params=new HashMap<String,Object>();
        params.put("sku",sku);params.put("pattern","%"+cleaned.toLowerCase(Locale.ROOT)+"%");params.put("fob",includeFob);
        // Historical raw dates can be blank or invalid. Never cast them without validation,
        // and never substitute updated_at for the source's quotation date.
        String normalized="translate(btrim(payload->>'quotationDate'),'/．.','---')";
        String date="(case when "+normalized+" ~ '^[0-9]{4}-[0-9]{1,2}-[0-9]{1,2}$' and pg_input_is_valid("+normalized+",'date') then cast("+normalized+" as date) end)";
        if(dateFrom!=null){filter+=" and "+date+">=cast(:dateFrom as date)";params.put("dateFrom",dateFrom.toString());}
        if(dateTo!=null){filter+=" and "+date+"<=cast(:dateTo as date)";params.put("dateTo",dateTo.toString());}
        return new Selection(filter,params);
    }

    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public byte[] export(String query,boolean includeFob,LocalDate dateFrom,LocalDate dateTo) throws IOException {
        var selection=selection(query,includeFob,dateFrom,dateTo);
        // One database snapshot, all matching rows, and only the small export fields (no images).
        var rows=jdbc.sql("""
            select sku,payload->>'quotationDate' as quotation_date,payload->>'quotationOwner' as owner,
                   payload->>'category' as category,source,kind,
                   jsonb_build_object(
                     'dataSource',payload->'dataSource','minOrderQty',payload->'minOrderQty',
                     'purchasePriceCny',payload->'purchasePriceCny',
                     'tier2MinQty',payload->'tier2MinQty','tier2PriceCny',payload->'tier2PriceCny',
                     'tier3MinQty',payload->'tier3MinQty','tier3PriceCny',payload->'tier3PriceCny',
                     'tier4MinQty',payload->'tier4MinQty','tier4PriceCny',payload->'tier4PriceCny',
                     'tier5MinQty',payload->'tier5MinQty','tier5PriceCny',payload->'tier5PriceCny',
                     'purchasePriceBasis',payload->'purchasePriceBasis','sourceQuotedPriceCny',payload->'sourceQuotedPriceCny',
                     'fobTiers',payload->'parsed'->'priceTiers') as prices from (
              select sku,payload,updated_at,'purchase' as kind,
                     case when payload->>'dataSource'='legacy_2026' then '2026旧数据' else '新数据' end as source
              from purchase_product where %s
              union all
              select sku,payload,updated_at,'fob' as kind,'FOB' as source from fob_purchase_product where :fob and %s
            ) matches order by updated_at desc,sku,kind limit 1048576
            """.formatted(selection.filter(),selection.filter())).params(selection.params())
            .query((rs,n)-> {
                var row=new ArrayList<String>(Arrays.asList(rs.getString("sku"),rs.getString("quotation_date"),rs.getString("owner"),rs.getString("category"),rs.getString("source")));
                row.addAll(PurchaseCatalogExportPrices.tiers(mapper.readTree(rs.getString("prices")),"fob".equals(rs.getString("kind"))));
                return row;
            }).list();
        if(rows.size()>1048575)throw AppException.unprocessable("筛选结果超过 Excel 行数上限，请缩小报价日期范围后下载");
        try(var book=new SXSSFWorkbook(100);var out=new ByteArrayOutputStream()) {
            var sheet=book.createSheet("采购SKU");
            var headers=new ArrayList<>(List.of("SKU","报价日期","报价人","类别","数据来源"));
            int tierCount=Math.max(5,rows.stream().mapToInt(row->row.size()-5).max().orElse(0));
            for(int tier=1;tier<=tierCount;tier++)headers.add("第"+tier+"档（数量 / 单价）");
            var style=book.createCellStyle();var font=book.createFont();font.setBold(true);style.setFont(font);
            var header=sheet.createRow(0);
            for(int col=0;col<headers.size();col++){var cell=header.createCell(col);cell.setCellValue(headers.get(col));cell.setCellStyle(style);sheet.setColumnWidth(col,(col>=5?48:col==0?30:20)*256);}
            for(int i=0;i<rows.size();i++){var row=sheet.createRow(i+1);var values=rows.get(i);for(int col=0;col<headers.size();col++)row.createCell(col).setCellValue(col<values.size()?Objects.toString(values.get(col),""):"");}
            sheet.createFreezePane(0,1);sheet.setAutoFilter(new CellRangeAddress(0,rows.size(),0,headers.size()-1));
            book.write(out);return out.toByteArray();
        }
    }
    private record Selection(String filter,Map<String,Object> params) {}
    private record Match(String kind,String sku,long total) {}
}
