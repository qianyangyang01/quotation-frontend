package com.milano.quotation.imports;

import com.milano.quotation.common.AppException;
import com.milano.quotation.storage.AssetStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AsyncPurchaseImportServiceTest {
    private ImportJobRepository jobs;
    private PurchaseImportRowRepository rows;
    private ImportPartRepository parts;
    private MigrationManifestEntryRepository images;
    private AssetStorageService storage;
    private AsyncPurchaseImportService service;

    @BeforeEach void setup() {
        jobs=mock(ImportJobRepository.class); rows=mock(PurchaseImportRowRepository.class);
        parts=mock(ImportPartRepository.class); images=mock(MigrationManifestEntryRepository.class);
        storage=mock(AssetStorageService.class);
        service=new AsyncPurchaseImportService(jobs,rows,parts,images,storage,JsonMapper.builder().build());
        when(jobs.save(any())).thenAnswer(i->i.getArgument(0));
    }

    @Test void createsQueuedJobAndSanitizesSourceName() {
        var file=new MockMultipartFile("file","folder\\purchase\n.xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",new byte[]{1,2,3});
        var job=service.create(file,"ADMIN");
        assertEquals("queued",job.status); assertEquals("folder_purchase_.xlsx",job.sourceName);
        assertEquals(64,job.sourceHash.length()); assertEquals(0,job.payload.path("totalRows").asInt());
        verify(storage).putRaw(startsWith("purchase-import/"),any(),eq(3L),contains("spreadsheet"));
    }

    @Test void rejectsMissingWrongAndOversizeFiles() {
        assertThrows(AppException.class,()->service.create(new MockMultipartFile("f","","",new byte[0]),"A"));
        assertThrows(AppException.class,()->service.create(new MockMultipartFile("f","a.csv","",new byte[]{1}),"A"));
        var huge=mock(org.springframework.web.multipart.MultipartFile.class);
        when(huge.isEmpty()).thenReturn(false); when(huge.getOriginalFilename()).thenReturn("a.xlsx");
        when(huge.getSize()).thenReturn(101L*1024*1024);
        assertThrows(AppException.class,()->service.create(huge,"A"));
    }

    @Test void confirmsOnlyReadyNonEmptyJob() {
        var job=job("ready"); job.validRows=3; when(jobs.findById(job.id)).thenReturn(Optional.of(job));
        assertEquals("import-queued",service.confirm(job.id).status); assertNotNull(job.confirmedAt);
        job.status="ready"; job.validRows=0; assertThrows(AppException.class,()->service.confirm(job.id));
        job.status="queued"; assertThrows(AppException.class,()->service.confirm(job.id));
    }

    @ParameterizedTest @ValueSource(strings={"queued","ready","failed"})
    void cancelsJobsThatHaveNotStartedImport(String status) {
        var job=job(status); when(jobs.findById(job.id)).thenReturn(Optional.of(job));
        assertEquals("cancelled",service.cancel(job.id).status); assertTrue(job.cancelRequested); assertNotNull(job.completedAt);
    }

    @Test void parsingCancellationIsRequestedAndImportingCannotCancel() {
        var job=job("parsing"); when(jobs.findById(job.id)).thenReturn(Optional.of(job));
        assertEquals("parsing",service.cancel(job.id).status); assertTrue(job.cancelRequested);
        job.status="importing"; assertThrows(AppException.class,()->service.cancel(job.id));
    }

    @ParameterizedTest @ValueSource(strings={"parsing","importing","rolling-back"})
    void retriesFailedPhaseAtCorrectQueue(String failedPhase) {
        var job=job("failed"); ((tools.jackson.databind.node.ObjectNode)job.payload).put("failedPhase",failedPhase);
        when(jobs.findById(job.id)).thenReturn(Optional.of(job));
        var expected=failedPhase.equals("parsing")?"queued":failedPhase.equals("importing")?"import-queued":"rollback-queued";
        assertEquals(expected,service.retry(job.id).status);
        job.status="ready"; assertThrows(AppException.class,()->service.retry(job.id));
    }

    @Test void queuesRollbackOnlyForCompletedResults() {
        var job=job("completed-with-errors"); when(jobs.findById(job.id)).thenReturn(Optional.of(job));
        assertEquals("rollback-queued",service.requestRollback(job.id).status);
        job.status="ready"; assertThrows(AppException.class,()->service.requestRollback(job.id));
    }

    @Test void transitionsAndFailureArePersistedInPayload() {
        var job=job("queued"); job.totalRows=9; job.validRows=8; when(jobs.findById(job.id)).thenReturn(Optional.of(job));
        service.prepareParsing(job.id); verify(rows).deleteByJobId(job.id); assertEquals("parsing",job.status); assertEquals(0,job.totalRows);
        service.markImporting(job.id); assertEquals("images",job.phase);
        service.markRollingBack(job.id); assertEquals("rolling-back",job.status);
        service.markFailed(job.id,"rollback",new IllegalStateException()); assertEquals("failed",job.status); assertEquals("IllegalStateException",job.errorMessage);
        assertEquals("rollback",job.payload.path("failedPhase").asText());
    }

    @Test void recoversStaleWorkersToTheirQueue() {
        var parsing=job("parsing"); var importing=job("importing"); var rolling=job("rolling-back");
        when(jobs.findByJobTypeAndStatusInAndHeartbeatAtBefore(eq(AsyncPurchaseImportService.JOB_TYPE),any(),any())).thenReturn(List.of(parsing,importing,rolling));
        service.recoverStaleJobs();
        assertEquals("queued",parsing.status); assertEquals("import-queued",importing.status); assertEquals("rollback-queued",rolling.status);
    }

    @Test void exposesPagedRowsImagesAndJobViews() {
        var job=job("ready"); when(jobs.findById(job.id)).thenReturn(Optional.of(job));
        var row=new PurchaseImportRow(); row.sourceRow=2; row.sku="SKU-1"; row.validationStatus="error"; row.importAction="skip"; row.errorMessage="bad"; row.payload=JsonMapper.builder().build().createObjectNode();
        var page=PageRequest.of(0,20);
        when(rows.findByJobIdOrderBySourceRow(job.id,page)).thenReturn(new PageImpl<>(List.of(row),page,1));
        when(rows.findByJobIdAndValidationStatusOrderBySourceRow(job.id,"error",page)).thenReturn(new PageImpl<>(List.of(row),page,1));
        var image=new MigrationManifestEntry(); image.status="failed"; image.sku="SKU-1"; image.imageType="product"; image.fileName="SKU-1-product.png"; image.errorMessage="bad image";
        when(images.findByJobIdOrderByFileName(job.id)).thenReturn(List.of(image)); when(parts.findByJobIdOrderByPartNumber(job.id)).thenReturn(List.of(new ImportPart()));
        when(images.countByJobIdAndStatus(job.id,"failed")).thenReturn(1L);
        assertEquals(1,service.rowPage(job.id,"",page).getTotalElements()); assertEquals(1,service.rowPage(job.id,"error",page).getTotalElements());
        assertEquals(1,service.imageErrors(job.id).size()); assertEquals(1,service.view(job.id).imageParts());
        when(jobs.findByJobTypeOrderByCreatedAtDesc(AsyncPurchaseImportService.JOB_TYPE,page)).thenReturn(new PageImpl<>(List.of(job),page,1));
        assertEquals(1,service.list(page).getTotalElements());
    }

    @Test void hidesMissingAndForeignJobs() {
        var id=UUID.randomUUID(); when(jobs.findById(id)).thenReturn(Optional.empty()); assertThrows(AppException.class,()->service.view(id));
        var foreign=job("ready"); foreign.jobType="other"; when(jobs.findById(foreign.id)).thenReturn(Optional.of(foreign)); assertThrows(AppException.class,()->service.view(foreign.id));
    }

    private static ImportJob job(String status) {
        var j=new ImportJob(); j.id=UUID.randomUUID(); j.jobType=AsyncPurchaseImportService.JOB_TYPE; j.status=status; j.phase=status;
        j.sourceName="a.xlsx"; j.sourceHash="hash"; j.requestedBy="ADMIN"; j.createdAt=Instant.now(); j.updatedAt=j.createdAt;
        j.payload=JsonMapper.builder().build().createObjectNode(); return j;
    }
}
