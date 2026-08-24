package com.milano.quotation.logistics;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogisticsRealWorkbookCorpusTest {
    @Test
    void preflightsAllSixtySixAuthoritativeWorkbooksAndIdentifiesKnownSourceDefect() throws Exception {
        var configured = System.getProperty("logistics.corpus", "").trim();
        Assumptions.assumeTrue(!configured.isEmpty(), "仅在提供 -Dlogistics.corpus 时运行真实物流模板语料测试");
        var root = Path.of(configured);
        Assumptions.assumeTrue(Files.isDirectory(root), "物流模板语料目录不存在");
        var service = new LogisticsWorkbookService(JsonMapper.builder().build());
        var files = Files.walk(root).filter(path -> path.getFileName().toString().toLowerCase().endsWith(".xlsx")).sorted().toList();

        assertEquals(66, files.size(), "权威模板数量必须保持为66份");
        var blockedFiles = 0;
        var unexpected = new java.util.ArrayList<String>();
        for (var path : files) {
            var upload = new MockMultipartFile("file", path.getFileName().toString(), "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", Files.readAllBytes(path));
            var result = service.parse(upload, JsonMapper.builder().build().createArrayNode());
            assertTrue(result.path("validRows").asInt() > 0, () -> path + " 没有有效价格行");
            if (path.getFileName().toString().equals("云途中包专线普货.xlsx")) {
                blockedFiles++;
                assertEquals(3, result.path("errors").asInt(), "已知源文件必须精确拦截3个无效重量区间");
                assertEquals("46,47,48", java.util.stream.StreamSupport.stream(result.path("issues").spliterator(), false)
                        .filter(issue -> "error".equals(issue.path("level").asText())).map(issue -> issue.path("row").asText()).collect(java.util.stream.Collectors.joining(",")));
            } else if (path.getFileName().toString().equals("云途挂号标快带电.xlsx")) {
                blockedFiles++;
                assertEquals(59, result.path("errors").asInt(), "已知源文件中59个重量点不能被猜测为计费区间");
                assertEquals(4, result.path("issues").get(0).path("row").asInt());
                assertEquals(62, result.path("issues").get(58).path("row").asInt());
            } else if (path.getFileName().toString().equals("云途服装.xlsx")) {
                blockedFiles++;
                assertEquals(15, result.path("errors").asInt(), "澳大利亚重量区间重复且价格冲突必须拦截");
                assertEquals(72, result.path("issues").get(0).path("row").asInt());
                assertEquals(86, result.path("issues").get(14).path("row").asInt());
            } else if (path.getFileName().toString().equals("燕文专线追踪特货.xlsx")) {
                blockedFiles++;
                assertEquals(1, result.path("errors").asInt(), "第49行缺少必填国家简码必须拦截");
                assertEquals(49, result.path("issues").get(0).path("row").asInt());
            } else if (path.getFileName().toString().equals("燕文精品特货.xlsx")) {
                blockedFiles++;
                assertEquals(3, result.path("errors").asInt(), "德国的三个完全重复价格段必须拦截");
                assertEquals(14, result.path("issues").get(0).path("row").asInt());
                assertEquals(16, result.path("issues").get(2).path("row").asInt());
            } else if (result.path("errors").asInt() > 0) unexpected.add(path.getFileName() + " errors=" + result.path("errors").asInt() + " issues=" + result.path("issues"));
            assertEquals(result.path("validRows").asInt(), result.path("summary").path("added").asInt(), () -> path + " 首次导入必须全部计为新增");
        }
        assertTrue(unexpected.isEmpty(), () -> "存在非预期阻断错误：\n" + String.join("\n", unexpected));
        assertEquals(5, blockedFiles, "66份基准中只允许已确认的5份源文件被预检拦截");
    }
}
