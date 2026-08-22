package com.milano.quotation.storage;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;import java.util.UUID;

@RestController @RequestMapping("/api/v1/assets")
public class AssetController{
    private final AssetStorageService storage;public AssetController(AssetStorageService storage){this.storage=storage;}
    @GetMapping("/{id}")void get(@PathVariable UUID id,HttpServletResponse response)throws IOException{var opened=storage.open(id);response.setContentType(opened.asset().mediaType);response.setContentLengthLong(opened.asset().sizeBytes);response.setHeader("Cache-Control","private, max-age=3600");response.setHeader("X-Content-Type-Options","nosniff");try(var input=opened.stream()){input.transferTo(response.getOutputStream());}}
}
