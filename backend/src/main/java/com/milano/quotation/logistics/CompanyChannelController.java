package com.milano.quotation.logistics;

import com.milano.quotation.audit.AuditService;
import com.milano.quotation.common.ApiResponse;
import com.milano.quotation.security.QuotationPrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.node.ObjectNode;
import java.util.*;

@RestController
@RequestMapping("/api/v1/logistics/company-channels")
@PreAuthorize("hasAuthority('PERM_logistics')")
public class CompanyChannelController {
    private final CompanyChannelService directory;private final CompanyPriceRebuildService rebuild;private final AuditService audit;
    public CompanyChannelController(CompanyChannelService directory,CompanyPriceRebuildService rebuild,AuditService audit){this.directory=directory;this.rebuild=rebuild;this.audit=audit;}
    @GetMapping public ApiResponse<?> list(){return ApiResponse.ok(directory.list());}
    @GetMapping("/baseline") public ApiResponse<?> baseline(){return ApiResponse.ok(rebuild.baseline());}
    @PutMapping public ApiResponse<?> save(@RequestBody ObjectNode input,Authentication auth){var result=directory.save(input,actor(auth));audit.record("logistics.company-directory","logistics-company-revision",result.path("revision").asText(),"success",Map.of("channels",result.path("entries").size()));return ApiResponse.ok(result);}
    @GetMapping("/rebuild/preview") public ApiResponse<?> preview(){return ApiResponse.ok(rebuild.preview());}
    @PostMapping("/rebuild") public ApiResponse<?> begin(@RequestBody ObjectNode input,Authentication auth){var result=rebuild.begin(input,actor(auth));record("pause",result,auth);return ApiResponse.ok(result);}
    @GetMapping("/rebuild/{id}") public ApiResponse<?> job(@PathVariable UUID id){return ApiResponse.ok(rebuild.job(id));}
    @PostMapping("/rebuild/{id}/backup") public ApiResponse<?> backup(@PathVariable UUID id,Authentication auth){var result=rebuild.backup(id);record("backup",result,auth);return ApiResponse.ok(result);}
    @PostMapping("/rebuild/{id}/purge") public ApiResponse<?> purge(@PathVariable UUID id,@RequestBody ObjectNode input,Authentication auth){var result=rebuild.purge(id,input);record("purge",result,auth);return ApiResponse.ok(result);}
    @PostMapping("/rebuild/{id}/cleanup") public ApiResponse<?> cleanup(@PathVariable UUID id,Authentication auth){var result=rebuild.cleanupObjects(id);record("cleanup",result,auth);return ApiResponse.ok(result);}
    @PostMapping("/rebuild/{id}/finish") public ApiResponse<?> finish(@PathVariable UUID id,@RequestBody ObjectNode input,Authentication auth){var result=rebuild.finish(id,input,actor(auth));record("finish",result,auth);return ApiResponse.ok(result);}
    @PostMapping("/rebuild/{id}/restore") public ApiResponse<?> restore(@PathVariable UUID id,@RequestBody ObjectNode input,Authentication auth){var result=rebuild.restore(id,input,actor(auth));record("restore",result,auth);return ApiResponse.ok(result);}
    private void record(String action,ObjectNode result,Authentication auth){audit.record("logistics.company-rebuild-"+action,"logistics-company-rebuild",result.path("id").asText(),"success",Map.of("actor",actor(auth),"phase",result.path("phase").asText()));}
    private static String actor(Authentication auth){return ((QuotationPrincipal)auth.getPrincipal()).account();}
}
