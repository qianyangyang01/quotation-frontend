package com.milano.quotation.quote;

import com.sun.net.httpserver.HttpServer;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;

/** Local-only acceptance harness. Uses a restored historical snapshot and SELECT-only DB role.
 * Not included in the production jar. It never proxies requests to production. */
public class ChannelRankingLocalServer {
 public static void main(String[] args)throws Exception {
  var ds=new HikariDataSource();ds.setJdbcUrl(System.getenv("RANKING_JDBC_URL"));ds.setUsername("ranking_reader");ds.setPassword(System.getenv("RANKING_DB_PASSWORD"));
  ds.setMaximumPoolSize(2);ds.setReadOnly(true);
  var mapper=new ObjectMapper();var jdbc=JdbcClient.create(ds);
  var ranking=new QuotationChannelRanking(ds,mapper);var analytics=new QuotationAnalyticsController(jdbc,mapper);
  var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));tx.setReadOnly(true);tx.setTimeout(8);
  var server=HttpServer.create(new InetSocketAddress("0.0.0.0",8088),0);
  server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
  server.createContext("/",exchange->{
   int status=200;Object data;
   try {
    if(!exchange.getRequestMethod().equals("GET")) {status=403;data=Map.of("message","本地只读副本，禁止写入");}
    else {
     String path=exchange.getRequestURI().getPath();var q=new HashMap<String,String>();
     String query=exchange.getRequestURI().getRawQuery();
     if(query!=null)for(String item:query.split("&")){String[] pair=item.split("=",2);q.put(URLDecoder.decode(pair[0],StandardCharsets.UTF_8),URLDecoder.decode(pair.length==2?pair[1]:"",StandardCharsets.UTF_8));}
     data=switch(path) {
      case "/health" -> Map.of("status","UP","source","local historical snapshot 2026-09-23");
      case "/api/v1/auth/me" -> Map.of("id","local-snapshot","account","LOCAL-READONLY","name","本地历史副本","role","super_admin","permissions",List.of("allRecords","logistics"),"mustChangePassword",false);
      case "/api/v1/review-notifications" -> Map.of("items",List.of(),"unread",List.of(),"counts",Map.of(),"total",0,"page",0,"totalPages",0);
      case "/api/v1/quotations/channel-ranking" -> tx.execute(s->ranking.read(new QuotationChannelRanking.Filters(date(q.get("startDate")),date(q.get("endDate")),q.get("country"),q.get("salesperson"),q.get("category"),q.get("keyword")),Integer.parseInt(q.getOrDefault("page","0")),Integer.parseInt(q.getOrDefault("size","5")),Boolean.parseBoolean(q.getOrDefault("pendingOnly","false"))));
      case "/api/v1/quotations/analytics-records" -> tx.execute(s->analytics.snapshot().data());
      case "/api/v1/purchase-products/analytics-catalog" -> {
       var rows=jdbc.sql("select jsonb_build_object('sku',sku,'category',payload->'category','purchasePriceCny',payload->'purchasePriceCny')::text from purchase_product order by sku").query((rs,n)->mapper.readTree(rs.getString(1))).list();yield Map.of("items",rows,"total",rows.size());
      }
      default -> {status=404;yield Map.of("message","本地验收仅开放概览读取接口，其他操作尚未启用");}
     };
    }
   }catch(Exception error){status=500;data=Map.of("error",error.getClass().getSimpleName(),"message",String.valueOf(error.getMessage()));}
   byte[] body=mapper.writeValueAsBytes(Map.of("code",status==200?"OK":"LOCAL_READ_ONLY","message",status==200?"":"本地接口暂不可用","data",data));
   exchange.getResponseHeaders().set("Content-Type","application/json;charset=utf-8");exchange.getResponseHeaders().set("Cache-Control","no-store");
   exchange.sendResponseHeaders(status,body.length);exchange.getResponseBody().write(body);exchange.close();
  });server.start();System.out.println("LOCAL_READ_ONLY_RANKING_READY");
 }
 private static LocalDate date(String value){return value==null||value.isBlank()?null:LocalDate.parse(value);}
}
