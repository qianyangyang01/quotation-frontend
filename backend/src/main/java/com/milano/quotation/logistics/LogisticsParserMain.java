package com.milano.quotation.logistics;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import tools.jackson.databind.ObjectMapper;

/** Internal, credential-free parser process. It has no database or business write access. */
public final class LogisticsParserMain {
    public static void main(String[] args)throws Exception {
        var mapper=new ObjectMapper();var parser=new LogisticsSourceParser(mapper,new LogisticsWorkbookService(mapper));
        var server=HttpServer.create(new InetSocketAddress(8090),8);
        var busy=new AtomicBoolean();var timer=Executors.newSingleThreadScheduledExecutor();
        server.setExecutor(Executors.newFixedThreadPool(2));
        server.createContext("/health",exchange->{exchange.sendResponseHeaders(200,2);exchange.getResponseBody().write("OK".getBytes(StandardCharsets.UTF_8));exchange.close();});
        server.createContext("/parse",exchange->{
            if(!exchange.getRequestMethod().equals("POST")){exchange.sendResponseHeaders(405,-1);exchange.close();return;}
            if(!busy.compareAndSet(false,true)){exchange.sendResponseHeaders(429,-1);exchange.close();return;}
            var deadline=timer.schedule(()->Runtime.getRuntime().halt(124),120,TimeUnit.SECONDS);
            try {
                var name=URLDecoder.decode(exchange.getRequestHeaders().getFirst("X-Workbook-Name"),StandardCharsets.UTF_8);
                byte[] bytes=exchange.getRequestBody().readNBytes((int)LogisticsSourceParser.MAX_FILE_BYTES+1);
                var result=parser.parse(bytes,name);
                exchange.getResponseHeaders().set("Content-Type","application/json");
                exchange.sendResponseHeaders(200,0);mapper.writeValue(exchange.getResponseBody(),result);
            } catch(Exception error) {
                var body=mapper.writeValueAsBytes(mapper.createObjectNode().put("error",String.valueOf(error.getMessage())));
                exchange.sendResponseHeaders(422,body.length);exchange.getResponseBody().write(body);
            } finally {deadline.cancel(false);busy.set(false);exchange.close();}
        });
        server.start();
    }
}
