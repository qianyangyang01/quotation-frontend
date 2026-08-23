package com.milano.quotation.imports;

import com.milano.quotation.common.AppException;
import com.milano.quotation.storage.AssetStorageService;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ImageMigrationServiceTest {
    private ImportJobRepository jobs;
    private MigrationManifestEntryRepository entries;
    private ImportPartRepository parts;
    private AssetStorageService storage;
    private ImageMigrationService service;

    @BeforeEach
    void setup() {
        jobs = mock(ImportJobRepository.class);
        entries = mock(MigrationManifestEntryRepository.class);
        parts = mock(ImportPartRepository.class);
        storage = mock(AssetStorageService.class);
        service = new ImageMigrationService(jobs, entries, parts, storage, new ObjectMapper());
        when(jobs.save(any())).thenAnswer(call -> call.getArgument(0));
        when(parts.save(any())).thenAnswer(call -> call.getArgument(0));
        when(entries.saveAll(any())).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void createsValidatedManifestAndStoresSource() throws Exception {
        var file = manifest(List.of(
                new String[]{"SKU-1", "产品图", "images/a.png", "a".repeat(64)},
                new String[]{"SKU-2", "physical", "b.jpg", ""}));
        var job = service.create(file, "ADMIN");
        assertEquals("awaiting-parts", job.status);
        assertEquals(2, job.payload.path("manifestRows").asInt());
        verify(entries).saveAll(argThat(value -> ((List<?>) value).size() == 2));
        verify(storage).putRaw(contains("manifest.xlsx"), any(), eq(file.getSize()), contains("spreadsheet"));
    }

    @Test
    void rejectsInvalidManifestHeadersRowsHashesAndPaths() throws Exception {
        assertThrows(AppException.class, () -> service.create(new MockMultipartFile("manifest", "a.csv", "text/csv", new byte[]{1}), "ADMIN"));
        assertThrows(AppException.class, () -> service.create(manifestWithHeader("错误表头"), "ADMIN"));
        assertThrows(AppException.class, () -> service.create(manifest(List.<String[]>of(new String[]{"", "产品图", "a.png", ""})), "ADMIN"));
        assertThrows(AppException.class, () -> service.create(manifest(List.<String[]>of(new String[]{"SKU", "错误类型", "a.png", ""})), "ADMIN"));
        assertThrows(AppException.class, () -> service.create(manifest(List.<String[]>of(new String[]{"SKU", "产品图", "../a.png", ""})), "ADMIN"));
        assertThrows(AppException.class, () -> service.create(manifest(List.<String[]>of(new String[]{"SKU", "产品图", "a.png", "bad"})), "ADMIN"));
        assertThrows(AppException.class, () -> service.create(manifest(List.of(
                new String[]{"SKU-1", "产品图", "a.png", ""},
                new String[]{"SKU-2", "实物图", "a.png", ""})), "ADMIN"));
    }

    @Test
    void uploadsPartsAndUpdatesProgress() throws Exception {
        var job = job("awaiting-parts");
        when(jobs.findById(job.id)).thenReturn(Optional.of(job));
        when(parts.findByJobIdAndPartNumber(job.id, 1)).thenReturn(Optional.empty());
        when(parts.findByJobIdOrderByPartNumber(job.id)).thenReturn(List.of(mock(ImportPart.class)));
        var zip = new MockMultipartFile("file", "part-1.zip", "application/zip", new byte[]{1, 2, 3});
        var part = service.uploadPart(job.id, 1, zip);
        assertEquals(1, part.partNumber);
        assertEquals("uploading", job.status);
        assertEquals(1, job.payload.path("uploadedParts").asInt());
        verify(storage).putRaw(contains("00001.zip"), any(), eq(3L), eq("application/zip"));
        service.markProcessing(job.id);
        assertEquals("processing", job.status);
    }

    @Test
    void rejectsPartAndProcessingStateErrors() {
        var id = UUID.randomUUID();
        when(jobs.findById(id)).thenReturn(Optional.empty());
        var zip = new MockMultipartFile("file", "part.zip", "application/zip", new byte[]{1});
        assertThrows(AppException.class, () -> service.uploadPart(id, 1, zip));
        var job = job("completed");
        when(jobs.findById(job.id)).thenReturn(Optional.of(job));
        assertThrows(AppException.class, () -> service.uploadPart(job.id, 1, zip));
        job.status = "awaiting-parts";
        assertThrows(AppException.class, () -> service.uploadPart(job.id, 0, zip));
        assertThrows(AppException.class, () -> service.uploadPart(job.id, 1, new MockMultipartFile("file", "part.txt", "text/plain", new byte[]{1})));
        when(parts.findByJobIdAndPartNumber(job.id, 1)).thenReturn(Optional.of(mock(ImportPart.class)));
        assertThrows(AppException.class, () -> service.uploadPart(job.id, 1, zip));
        when(parts.findByJobIdOrderByPartNumber(job.id)).thenReturn(List.of());
        assertThrows(AppException.class, () -> service.markProcessing(job.id));
    }

    @Test
    void returnsJobViewAndOnlyFailedErrors() {
        var job = job("processing");
        when(jobs.findById(job.id)).thenReturn(Optional.of(job));
        when(jobs.existsById(job.id)).thenReturn(true);
        when(entries.countByJobIdAndStatus(job.id, "completed")).thenReturn(2L);
        when(entries.countByJobIdAndStatus(job.id, "failed")).thenReturn(1L);
        when(entries.countByJobIdAndStatus(job.id, "pending")).thenReturn(3L);
        when(parts.findByJobIdOrderByPartNumber(job.id)).thenReturn(List.of(mock(ImportPart.class)));
        var failed = entry(job.id, "failed", "bad image");
        var pending = entry(job.id, "pending", null);
        when(entries.findByJobIdOrderByFileName(job.id)).thenReturn(List.of(failed, pending));
        assertEquals(2, service.view(job.id).completed());
        assertEquals("bad image", service.errors(job.id).getFirst().error());
        when(jobs.existsById(UUID.randomUUID())).thenReturn(false);
        assertThrows(AppException.class, () -> service.errors(UUID.randomUUID()));
    }

    private ImportJob job(String status) {
        var job = new ImportJob();
        job.id = UUID.randomUUID(); job.jobType = "purchase-image-migration"; job.status = status;
        job.requestedBy = "ADMIN"; job.sourceName = "manifest.xlsx"; job.payload = new ObjectMapper().createObjectNode();
        job.createdAt = Instant.now(); job.updatedAt = job.createdAt;
        return job;
    }

    private MigrationManifestEntry entry(UUID jobId, String status, String error) {
        var entry = new MigrationManifestEntry();
        entry.id = UUID.randomUUID(); entry.jobId = jobId; entry.sku = "SKU-1"; entry.imageType = "product";
        entry.fileName = "a.png"; entry.status = status; entry.errorMessage = error; entry.updatedAt = Instant.now();
        return entry;
    }

    private MockMultipartFile manifestWithHeader(String firstHeader) throws Exception {
        try (var workbook = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var row = workbook.createSheet("manifest").createRow(0);
            row.createCell(0).setCellValue(firstHeader); row.createCell(1).setCellValue("图片类型");
            row.createCell(2).setCellValue("文件名"); row.createCell(3).setCellValue("SHA256");
            workbook.write(out);
            return new MockMultipartFile("manifest", "manifest.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", out.toByteArray());
        }
    }

    private MockMultipartFile manifest(List<String[]> values) throws Exception {
        try (var workbook = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("manifest");
            var header = sheet.createRow(0);
            String[] headers = {"SKU", "图片类型", "文件名", "SHA256"};
            for (int column = 0; column < headers.length; column++) header.createCell(column).setCellValue(headers[column]);
            for (int index = 0; index < values.size(); index++) {
                var row = sheet.createRow(index + 1);
                for (int column = 0; column < 4; column++) row.createCell(column).setCellValue(values.get(index)[column]);
            }
            workbook.write(out);
            return new MockMultipartFile("manifest", "manifest.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", out.toByteArray());
        }
    }
}
