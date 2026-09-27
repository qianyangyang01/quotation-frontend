package com.milano.quotation.purchase;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.nio.ByteBuffer;

@Component
class ShimoClient {
    private final String token;
    private final List<String> sheets;
    private final ObjectMapper mapper;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    ShimoClient(@Value("${app.shimo.token:}") String token,@Value("${app.shimo.sheets:}") String sheets,ObjectMapper mapper) {
        this.token=token; this.sheets=Arrays.stream(sheets.split(",")).map(String::trim).filter(s->!s.isEmpty()&&!Set.of("国际站","国际站批发报价").contains(s)).distinct().toList(); this.mapper=mapper;
    }
    boolean configured() { return !token.isBlank()&&!sheets.isEmpty(); }
    List<String> sheets() { return sheets; }
    JsonNode values(String sheet,String range) {
        if(!sheets.contains(sheet)) throw new IllegalArgumentException("工作表不在同步范围");
        String name="'"+sheet.replace("'","''")+"'!"+range;
        var uri=URI.create("https://shimo.im/lizard-api/files/"+ShimoRowMapper.FILE_GUID+"/sheets/values?range="+URLEncoder.encode(name,StandardCharsets.UTF_8));
        CompletableFuture<HttpResponse<byte[]>> pending=null;
        try {
            var request=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).header("Authorization","Bearer "+token).GET().build();
            pending=http.sendAsync(request,info->limitedBody());
            var response=pending.get(30,TimeUnit.SECONDS);
            if(response.statusCode()!=200) throw new IllegalStateException("石墨读取失败 HTTP "+response.statusCode()+"；本轮未应用数据");
            var result=mapper.readTree(response.body()).path("values");
            if(!result.isArray()) throw new IllegalStateException("石墨接口数据格式异常");
            return result;
        } catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("同步已中断"); }
        catch(TimeoutException e) {throw new IllegalStateException("石墨读取超过30秒，保留进度等待重试");}
        catch(ExecutionException e) { throw new IllegalStateException("石墨网络读取失败或响应过大，保留进度等待重试"); }
        finally {if(pending!=null&&!pending.isDone()) pending.cancel(true);}
    }
    private static HttpResponse.BodySubscriber<byte[]> limitedBody() {
        return new HttpResponse.BodySubscriber<>() {
            final HttpResponse.BodySubscriber<byte[]> delegate=HttpResponse.BodySubscribers.ofByteArray();
            Flow.Subscription subscription;long size;
            public CompletionStage<byte[]> getBody(){return delegate.getBody();}
            public void onSubscribe(Flow.Subscription value){subscription=value;delegate.onSubscribe(value);}
            public void onNext(List<ByteBuffer> buffers){
                for(var buffer:buffers) size+=buffer.remaining();
                if(size>8_000_000){subscription.cancel();delegate.onError(new java.io.IOException("response size limit"));}
                else delegate.onNext(buffers);
            }
            public void onError(Throwable error){delegate.onError(error);}
            public void onComplete(){delegate.onComplete();}
        };
    }
    interface PageCache {
        void prepare(String sheet,JsonNode index);
        JsonNode read(String sheet,int first,int last);
        void write(String sheet,int first,int last,JsonNode rows);
    }
    List<SourceRow> readAll(PageCache cache) {
        return readAll(cache,sku->true);
    }
    List<SourceRow> readAll(PageCache cache,java.util.function.Predicate<String> needed) {
        var result=new ArrayList<SourceRow>();
        for(var sheet:sheets) {
            ShimoRowMapper.validateHeader(values(sheet,"D1:AI1").path(0));
            var index=values(sheet,"G2:G5001");
            // Explicitly refuse a possibly truncated index rather than silently missing later rows.
            if(index.size()>=5000) throw new IllegalStateException(sheet+"已达5000行扫描上限，需调整后再同步");
            cache.prepare(sheet,index);
            for(int offset=0;offset<index.size();offset+=150) {
                int length=Math.min(150,index.size()-offset);
                boolean wanted=false;
                for(int i=0;i<length;i++) {
                    var sku=ShimoRowMapper.text(index.path(offset+i),0).replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
                    if(!sku.isBlank()&&needed.test(sku)) {wanted=true;break;}
                }
                if(!wanted) continue;
                int first=offset+2,last=offset+length+1;
                var rows=cache.read(sheet,first,last);
                boolean fetched=rows==null;
                if(fetched) rows=values(sheet,"D"+first+":AI"+last);
                for(int i=0;i<length;i++) {
                    String expected=ShimoRowMapper.text(index.path(offset+i),0).replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
                    String actual=ShimoRowMapper.sku(rows.path(i));
                    if(!actual.equals(expected)) throw new IllegalStateException(sheet+"行位置在读取过程中变化；本轮未应用，请重试");
                    if(actual.isBlank()||!needed.test(actual)) continue;
                    if(actual.length()>96) throw new IllegalStateException(sheet+"第"+(offset+i+2)+"行 SKU 超长");
                    result.add(new SourceRow(sheet,offset+i+2,actual,rows.path(i)));
                }
                if(fetched) cache.write(sheet,first,last,rows);
                if(Thread.currentThread().isInterrupted()) throw new IllegalStateException("同步已中断");
            }
        }
        return result;
    }
    record SourceRow(String sheet,int row,String sku,JsonNode cells) {}
}
