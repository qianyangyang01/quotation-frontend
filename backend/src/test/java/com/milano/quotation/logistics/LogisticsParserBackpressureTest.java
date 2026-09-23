package com.milano.quotation.logistics;
import com.sun.net.httpserver.HttpServer;
import com.milano.quotation.common.AppException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class LogisticsParserBackpressureTest {
    @Test void temporaryCapacityRejectionRetriesTheSameStatelessParse() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var calls=new AtomicInteger();
        server.createContext("/parse",exchange->{
            exchange.getRequestBody().readAllBytes();int count=calls.incrementAndGet();
            byte[] body="{\"channels\":[]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(count<=2?429:200,body.length);exchange.getResponseBody().write(body);exchange.close();
        });server.start();
        try {
            var client=new LogisticsParserClient(null,new ObjectMapper(),"http://127.0.0.1:"+server.getAddress().getPort());
            assertTrue(client.parse(new byte[]{1},"prices.xlsx").has("channels"));assertEquals(3,calls.get());
        }finally{server.stop(0);}
    }
    @Test void malformedWorkbookIsNotRetried() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var calls=new AtomicInteger();
        server.createContext("/parse",exchange->{calls.incrementAndGet();exchange.getRequestBody().readAllBytes();exchange.sendResponseHeaders(422,-1);exchange.close();});server.start();
        try {
            var client=new LogisticsParserClient(null,new ObjectMapper(),"http://127.0.0.1:"+server.getAddress().getPort());
            assertThrows(AppException.class,()->client.parse(new byte[]{1},"bad.xlsx"));assertEquals(1,calls.get());
        }finally{server.stop(0);}
    }
}
