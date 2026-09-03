package com.milano.quotation.logistics;

import com.milano.quotation.common.AppException;
import org.junit.jupiter.api.Test;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LogisticsImportServiceTest {
    @Test void acceptsMultipleLargeFilesUpToTheExpandedBatchLimit(){
        var files=IntStream.range(0,4).mapToObj(index->file("物流商"+index+".xlsx",90L*1024*1024)).toList();
        assertDoesNotThrow(()->LogisticsImportService.validateFiles(files));
    }

    @Test void rejectsOversizedSingleFilesAndBatches(){
        assertThrows(AppException.class,()->LogisticsImportService.validateFiles(List.of(file("过大.xlsx",101L*1024*1024))));
        var files=IntStream.range(0,6).mapToObj(index->file("物流商"+index+".xlsx",90L*1024*1024)).toList();
        assertThrows(AppException.class,()->LogisticsImportService.validateFiles(files));
    }

    private static MultipartFile file(String name,long size){
        var file=mock(MultipartFile.class);when(file.getOriginalFilename()).thenReturn(name);when(file.getSize()).thenReturn(size);when(file.isEmpty()).thenReturn(false);return file;
    }
}
