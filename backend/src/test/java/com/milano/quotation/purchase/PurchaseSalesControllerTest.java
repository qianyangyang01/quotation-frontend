package com.milano.quotation.purchase;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PurchaseSalesControllerTest {
    @Configuration @EnableMethodSecurity
    static class Config {
        @Bean PurchaseProductRepository repository() { return mock(PurchaseProductRepository.class); }
        @Bean PurchaseSalesController controller(PurchaseProductRepository repo) throws Exception { return new PurchaseSalesController(repo, "classpath:purchase-sales-test.json"); }
    }
    @Test void sourceIsCompleteAndReadRefreshesWithoutMutatingProcurement() throws Exception {
        var repo = mock(PurchaseProductRepository.class);
        when(repo.salesCatalog(any())).thenReturn(List.of("{\"sku\":\"OIL\",\"weightG\":null}"), List.of("{\"sku\":\"OIL\",\"weightG\":100}"));
        var controller = new PurchaseSalesController(repo, "classpath:purchase-sales-test.json");
        var first = controller.get().data();
        assertEquals(3, first.source().path("rows").size());
        assertEquals(1, first.source().path("monthlyOnlySkus").size());
        long total = 0; int differences = 0;
        for (var row : first.source().path("rows")) { total += row.path("total").asLong(); if (row.path("salesDifference").asInt() != 0) differences++; }
        assertEquals(24, total); assertEquals(1, differences);
        assertTrue(first.products().getFirst().path("weightG").isNull());
        assertEquals(100, controller.get().data().products().getFirst().path("weightG").asInt());
        verify(repo, times(2)).salesCatalog(argThat(keys -> keys.size() == 3 && keys.contains("SALES-PRIMARY") && keys.contains("SALES-CHILD-ONLY")));
        verifyNoMoreInteractions(repo);
    }
    @Test void purchasePermissionIsRequiredBeforeReadingSalesData() {
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            var controller = context.getBean(PurchaseSalesController.class);
            var repo = context.getBean(PurchaseProductRepository.class);
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("employee", "", List.of(new SimpleGrantedAuthority("PERM_quote"))));
            assertThrows(AccessDeniedException.class, controller::get);
            verifyNoInteractions(repo);
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("buyer", "", List.of(new SimpleGrantedAuthority("PERM_purchase"))));
            when(repo.salesCatalog(any())).thenReturn(List.of());
            assertEquals(3, controller.get().data().source().path("rows").size());
        } finally { SecurityContextHolder.clearContext(); }
    }
    @Test void absentPrivateSourceDoesNotBecomeAnEmptySuccessfulReport() throws Exception {
        var repo = mock(PurchaseProductRepository.class);
        assertThrows(com.milano.quotation.common.AppException.class, () -> new PurchaseSalesController(repo, "").get());
        verifyNoInteractions(repo);
    }
}
