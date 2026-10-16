package com.milano.quotation.purchase;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static com.milano.quotation.purchase.PurchaseRankingOwners.Account;

class PurchaseRankingOwnersTest {
    @Test void mergesOnlyConfirmedAliasesAndExactIdentitiesForPurchaseRole() {
        var owners=new PurchaseRankingOwners(List.of(new Account("ZHENGKE","郑珂","purchase"),
            new Account("QIANYANGYANG","钱洋洋","super_admin"),new Account("SUNLIHONG","孙丽红","employee"),
            new Account("NEWBUYER","新采购","purchase")));
        for(var key:List.of("郑","郑珂"," zhengke "))assertEquals("郑珂",owners.resolve(key));
        assertEquals("新采购",owners.resolve("NEWBUYER"));
        for(var key:List.of("新","钱洋洋","孙丽红","感温款","","方文娟"))assertEquals("",owners.resolve(key));
        assertEquals("",new PurchaseRankingOwners(List.of(new Account("ZHENGKE","郑珂","employee"))).resolve("郑"));
    }
    @Test void neverGuessesDuplicateNamesOrAliasCollisions() {
        var owners=new PurchaseRankingOwners(List.of(new Account("A001","同名","purchase"),new Account("A002","同名","employee"),
            new Account("ZHENGKE","郑珂","purchase"),new Account("A003","郑","employee")));
        assertEquals("",owners.resolve("同名"));assertEquals("同名（A001）",owners.resolve("A001"));assertEquals("",owners.resolve("郑"));
    }
    @Test void normalizesReviewProjectionsWithoutChangingOriginalHistory() {
        var owners=new PurchaseRankingOwners(List.of(new Account("ZHENGKE","郑珂","purchase")));
        var event=new ObjectMapper().readTree("""
            {"priceException":{"targets":[{"procurementOwner":"郑"}]},
             "itemChanges":[{"priceException":{"targets":[{"procurementOwner":"zhengke"},{"procurementOwner":"钱洋洋"}]}}]}
            """);
        var resolved=PurchaseRankingService.resolveEvent(event,owners);
        assertEquals("郑",event.at("/priceException/targets/0/procurementOwner").asText());
        assertEquals("郑珂",resolved.at("/priceException/targets/0/procurementOwner").asText());
        assertEquals("郑珂",resolved.at("/itemChanges/0/priceException/targets/0/procurementOwner").asText());
        assertEquals("",resolved.at("/itemChanges/0/priceException/targets/1/procurementOwner").asText());
    }
}
