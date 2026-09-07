package com.milano.quotation.logistics;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LogisticsParserClientTest {
    @Test void processFailureDoesNotInvokeParserInApiAndNextFileCanSucceed()throws Exception {
        var local=mock(LogisticsSourceParser.class);var mapper=new ObjectMapper();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/parse",exchange->{
            exchange.getRequestBody().readAllBytes();
            if(calls.incrementAndGet()==1){exchange.sendResponseHeaders(422,-1);exchange.close();return;}
            var body="{\"sheets\":[],\"channels\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
        });server.start();
        var client=new LogisticsParserClient(local,mapper,"http://127.0.0.1:"+server.getAddress().getPort());
        try {
            assertThrows(com.milano.quotation.common.AppException.class,()->client.parse(new byte[]{1},"异常.xlsx"));
            assertTrue(client.parse(new byte[]{2},"正常.xlsx").path("channels").isEmpty());
            verifyNoInteractions(local);
        } finally {server.stop(0);}
    }
}
