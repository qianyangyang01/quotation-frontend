package com.milano.quotation.logistics;

import com.milano.quotation.common.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

@RestController
@RequestMapping("/api/v1/logistics/published")
@PreAuthorize("hasAnyAuthority('PERM_quote','PERM_logistics')")
public class PublishedLogisticsController {
    private final LogisticsQueryService queries;

    public PublishedLogisticsController(LogisticsQueryService queries) { this.queries = queries; }

    @GetMapping("/manifest")
    ResponseEntity<ApiResponse<LogisticsQueryService.PublishedManifest>> manifest(
            @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
        var revision = queries.manifestRevision();
        var etag = weakEtag(revision.revision());
        if (matches(ifNoneMatch, etag)) return ResponseEntity.status(304).eTag(etag).varyBy("Accept-Encoding").build();
        var manifest = queries.manifest(revision);
        return ResponseEntity.ok().eTag(etag).varyBy("Accept-Encoding").cacheControl(org.springframework.http.CacheControl.noCache().cachePrivate()).body(ApiResponse.ok(manifest));
    }

    @GetMapping("/rules")
    ResponseEntity<ApiResponse<LogisticsQueryService.PublishedRules>> rules(
            @RequestParam(defaultValue = "") String revision,
            @RequestParam(defaultValue = "普货") String attribute,
            @RequestParam(name = "country", required = false) List<String> countries,
            @RequestParam(name = "channelCode", required = false) List<String> channelCodes,
            @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
        var result = queries.publishedRules(revision, attribute, countries, channelCodes);
        var etagValue = sha256(result.revision() + "|" + attribute + "|" + String.join(",", countries) + "|" + String.join(",", channelCodes == null ? List.of() : channelCodes));
        var etag = weakEtag(etagValue);
        if (matches(ifNoneMatch, etag)) return ResponseEntity.status(304).eTag(etag).varyBy("Accept-Encoding").build();
        return ResponseEntity.ok().eTag(etag).varyBy("Accept-Encoding").cacheControl(org.springframework.http.CacheControl.noCache().cachePrivate()).body(ApiResponse.ok(result));
    }

    @GetMapping("/catalog")
    ResponseEntity<ApiResponse<LogisticsQueryService.PublishedRules>> catalog(
            @RequestParam(defaultValue = "") String revision,
            @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
        var result = queries.publishedCatalog(revision);
        var etagValue = sha256(result.revision() + "|catalog");
        var etag = weakEtag(etagValue);
        if (matches(ifNoneMatch, etag)) return ResponseEntity.status(304).eTag(etag).varyBy("Accept-Encoding").build();
        return ResponseEntity.ok().eTag(etag).varyBy("Accept-Encoding").cacheControl(org.springframework.http.CacheControl.noCache().cachePrivate()).body(ApiResponse.ok(result));
    }

    // The revision identifies equivalent content, not gzip/identity wire bytes.
    // Strong ETags disable Tomcat compression. GET uses weak comparison and
    // accepts old strong validators so existing browser caches remain useful.
    private static String weakEtag(String value) { return "W/\"" + value + "\""; }
    private static boolean matches(String header, String etag) {
        if (header == null) return false;
        var opaque = etag.substring(2);
        for (var candidate : header.split(",")) {
            var value = candidate.trim();
            if (value.equals("*") || value.equals(opaque) || value.equals(etag)) return true;
        }
        return false;
    }
    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception exception) { throw new IllegalStateException("无法生成物流响应版本", exception); }
    }
}
