package com.milano.quotation.logistics;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.milano.quotation.common.AppException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
public class LogisticsParserClient {
    private final LogisticsSourceParser local;
    private final ObjectMapper mapper;
    private final String endpoint;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    public LogisticsParserClient(LogisticsSourceParser local,ObjectMapper mapper,@Value("${app.logistics.parser-url:}")String endpoint){this.local=local;this.mapper=mapper;this.endpoint=endpoint;}
    ObjectNode parse(byte[] bytes,String name)throws Exception {
        return parse(bytes,name,new CompanyChannelScope(mapper.createObjectNode().put("enabled",false)));
    }
    ObjectNode parse(byte[] bytes,String name,CompanyChannelScope scope)throws Exception {
        if(endpoint.isBlank())return scope.unrestricted()?local.parse(bytes,name):local.parse(bytes,name,scope);
        byte[] json=mapper.writeValueAsBytes(scope.snapshot());
        if(json.length>2*1024*1024)throw AppException.unprocessable("公司渠道清单过大");
        var bodyPublisher=HttpRequest.BodyPublishers.concat(HttpRequest.BodyPublishers.ofByteArray(java.nio.ByteBuffer.allocate(4).putInt(json.length).array()),HttpRequest.BodyPublishers.ofByteArray(json),HttpRequest.BodyPublishers.ofByteArray(bytes));
        var request=HttpRequest.newBuilder(URI.create(endpoint+"/parse")).timeout(Duration.ofSeconds(125))
            .header("X-Workbook-Name",URLEncoder.encode(name,StandardCharsets.UTF_8)).header("X-Company-Scope","length-prefixed-json-v1").POST(bodyPublisher).build();
        try {
            var response=http.send(request,HttpResponse.BodyHandlers.ofInputStream());
            try(var body=response.body()) {
                if(response.statusCode()!=200)throw AppException.unprocessable(response.statusCode()==429?"解析服务繁忙，请稍后重试此文件":"文件解析失败，请核对模板或解析证据");
                return (ObjectNode)mapper.readTree(body);
            }
        } catch(java.io.IOException error){throw AppException.unprocessable("独立解析进程中断或超时，此文件未完成；其他文件将继续处理");}
    }
}
