package com.milano.quotation.logistics;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertTrue;

class LogisticsParserPerformanceTest {
    @Test void realWorkbookWarmP95StaysBelowFiveSecondsAndWritesEnvironmentReport()throws Exception{
        var configured=System.getProperty("logistics.corpusDir","").trim();var runs=Integer.getInteger("logistics.performanceRuns",0);
        Assumptions.assumeTrue(!configured.isBlank()&&runs>0,"仅在提供真实语料目录和重复次数时运行性能验收");assertTrue(runs>=3,"性能P95至少需要3轮样本");
        var mapper=new ObjectMapper();var parser=new LogisticsSourceParser(mapper,new LogisticsWorkbookService(mapper));var root=Path.of(configured);
        var files=Files.list(root).filter(path->path.toString().matches("(?i).*\\.xlsx?$")&&!path.getFileName().toString().startsWith("~$")).sorted().toList();assertTrue(files.size()==11,"性能语料必须是11份真实工作簿");
        var report=mapper.createObjectNode().put("measuredAt",Instant.now().toString()).put("java",System.getProperty("java.version")).put("os",System.getProperty("os.name")+" "+System.getProperty("os.arch")).put("processors",Runtime.getRuntime().availableProcessors()).put("maxMemoryBytes",Runtime.getRuntime().maxMemory()).put("runs",runs).put("thresholdMs",5000);
        var results=report.putArray("files");
        for(var path:files){
            var bytes=Files.readAllBytes(path);parser.parse(bytes,path.getFileName().toString());var samples=new ArrayList<Long>();int sheets=0,rows=0;
            for(int run=0;run<runs;run++){long start=System.nanoTime();var parsed=parser.parse(bytes,path.getFileName().toString());samples.add((System.nanoTime()-start)/1_000_000);sheets=parsed.path("sheets").size();rows=0;for(var channel:parsed.path("channels"))rows+=channel.path("rows").size();}
            Collections.sort(samples);var p95=samples.get(Math.max(0,(int)Math.ceil(samples.size()*0.95)-1));var item=results.addObject().put("file",path.getFileName().toString()).put("bytes",bytes.length).put("sheets",sheets).put("rows",rows).put("p95Ms",p95);var values=item.putArray("samplesMs");samples.forEach(values::add);
            assertTrue(p95<5000,path.getFileName()+" warm P95="+p95+"ms 超过5秒");
        }
        var output=Path.of("target/logistics-performance/report.json");Files.createDirectories(output.getParent());Files.writeString(output,mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report));
    }
}
