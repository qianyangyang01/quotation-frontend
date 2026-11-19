package com.milano.quotation.quote;

import com.milano.quotation.common.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;

@RestController
@RequestMapping("/api/v1/quotations/channel-ranking")
public class QuotationChannelRankingController {
    private final QuotationChannelRanking rankings;
    public QuotationChannelRankingController(QuotationChannelRanking rankings){this.rankings=rankings;}
    @GetMapping
    @PreAuthorize("hasAuthority('PERM_allRecords')")
    public ApiResponse<QuotationChannelRanking.Result> list(
        @RequestParam(required=false) LocalDate startDate,@RequestParam(required=false) LocalDate endDate,
        @RequestParam(defaultValue="") String country,@RequestParam(defaultValue="") String salesperson,
        @RequestParam(defaultValue="") String category,@RequestParam(defaultValue="") String keyword,
        @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="5") int size,
        @RequestParam(defaultValue="false") boolean pendingOnly) {
        return ApiResponse.ok(rankings.read(new QuotationChannelRanking.Filters(startDate,endDate,country,salesperson,category,keyword),page,size,pendingOnly));
    }
}
