package com.milano.quotation.logistics;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPInputStream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PublishedLogisticsCompressionTest {
    @TempDir Path directory;

    @Test void realTomcatCompressesRulesAndAcceptsBothOldAndNewValidators() throws Exception {
        var mapper = new ObjectMapper();
        var queries = mock(LogisticsQueryService.class);
        var row = mapper.createObjectNode().put("name","物流规则").put("prices","1234567890".repeat(5000));
        when(queries.publishedRules("r1","普货",List.of("美国"),null))
                .thenReturn(new LogisticsQueryService.PublishedRules("r1",List.of(row)));
        var controller = new PublishedLogisticsController(queries);
        var tomcat = new Tomcat();
        tomcat.setBaseDir(directory.toString());
        tomcat.setPort(0);
        var connector = tomcat.getConnector();
        connector.setProperty("address","127.0.0.1");
        connector.setProperty("compression","on");
        connector.setProperty("compressionMinSize","1024");
        connector.setProperty("compressibleMimeType","application/json");
        var context = tomcat.addContext("",directory.toString());
        Tomcat.addServlet(context,"rules",new HttpServlet() {
            @Override protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
                var result = controller.rules("r1","普货",List.of("美国"),null,request.getHeader("If-None-Match"));
                response.setStatus(result.getStatusCode().value());
                response.setContentType("application/json");
                result.getHeaders().forEach((key,values) -> values.forEach(value -> response.addHeader(key,value)));
                if (result.getBody()!=null) response.getOutputStream().write(mapper.writeValueAsBytes(result.getBody()));
            }
        });
        context.addServletMappingDecoded("/rules","rules");
        try {
            tomcat.start();
            var uri = URI.create("http://127.0.0.1:"+connector.getLocalPort()+"/rules");
            try(var client = HttpClient.newHttpClient()) {
                var identity = client.send(HttpRequest.newBuilder(uri).header("Accept-Encoding","identity").build(),HttpResponse.BodyHandlers.ofByteArray());
                var gzip = client.send(HttpRequest.newBuilder(uri).header("Accept-Encoding","gzip").build(),HttpResponse.BodyHandlers.ofByteArray());
                assertEquals(200,gzip.statusCode());
                assertEquals("gzip",gzip.headers().firstValue("Content-Encoding").orElseThrow());
                assertTrue(gzip.body().length < identity.body().length / 2);
                try(var stream = new GZIPInputStream(new ByteArrayInputStream(gzip.body()))) {
                    // The response envelope contains a per-request timestamp;
                    // the versioned logistics data must be identical.
                    assertEquals(mapper.readTree(identity.body()).path("data"),mapper.readTree(stream.readAllBytes()).path("data"));
                }
                var etag = gzip.headers().firstValue("ETag").orElseThrow();
                assertTrue(etag.startsWith("W/"));
                for(var validator : List.of(etag,etag.substring(2),"\"old\", "+etag)) {
                    var unchanged = client.send(HttpRequest.newBuilder(uri).header("Accept-Encoding","gzip")
                            .header("If-None-Match",validator).build(),HttpResponse.BodyHandlers.ofByteArray());
                    assertEquals(304,unchanged.statusCode());
                    assertEquals(0,unchanged.body().length);
                }
            }
        } finally { tomcat.stop(); tomcat.destroy(); }
    }
}
