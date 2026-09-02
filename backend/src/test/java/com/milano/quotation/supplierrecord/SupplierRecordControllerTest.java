package com.milano.quotation.supplierrecord;

import com.milano.quotation.audit.AuditService;
import com.milano.quotation.common.AppException;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.core.Authentication;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SupplierRecordControllerTest {
    @Test
    void translatesSpringOptimisticLockFailureToConflictAndAuditsFailure() {
        var records = mock(SupplierRecordService.class);
        var audit = mock(AuditService.class);
        var authentication = mock(Authentication.class);
        var controller = new SupplierRecordController(records, audit);
        var id = UUID.randomUUID();

        when(authentication.getName()).thenReturn("BUYER");
        when(records.update(eq(id), eq(3L), isNull(), eq("BUYER")))
                .thenThrow(new ObjectOptimisticLockingFailureException(SupplierRecord.class, id));

        assertThatThrownBy(() -> controller.update(id, 3L, null, authentication))
                .isInstanceOf(AppException.class)
                .satisfies(error -> org.assertj.core.api.Assertions.assertThat(((AppException) error).status().value()).isEqualTo(409));

        verify(audit).recordIndependent(eq("supplier-record.update"), eq("supplier-record"), eq(id.toString()),
                eq("failure"), org.mockito.ArgumentMatchers.<Map<String, ?>>any());
    }

    @Test
    void auditsCreateValidationFailureWithoutSensitiveRequestFields() {
        var records = mock(SupplierRecordService.class);
        var audit = mock(AuditService.class);
        var authentication = mock(Authentication.class);
        var controller = new SupplierRecordController(records, audit);

        when(authentication.getName()).thenReturn("BUYER");
        when(records.create(isNull(), eq("BUYER"))).thenThrow(AppException.unprocessable("开票类型不合法"));

        assertThatThrownBy(() -> controller.create(null, authentication)).isInstanceOf(AppException.class);
        verify(audit).recordIndependent(eq("supplier-record.create"), eq("supplier-record"), isNull(),
                eq("failure"), org.mockito.ArgumentMatchers.<Map<String, ?>>any());
    }
}
