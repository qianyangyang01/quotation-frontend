package com.milano.quotation.supplierrecord;

import com.milano.quotation.common.AppException;
import com.milano.quotation.storage.AssetStorageService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.List;

@Service
public class SupplierRecordService {
    private final SupplierRecordRepository records;
    private final AssetStorageService storage;

    public SupplierRecordService(SupplierRecordRepository records, AssetStorageService storage) {
        this.records = records;
        this.storage = storage;
    }

    @Transactional(readOnly = true)
    public Page<SupplierRecordView> page(String query, String industryBelt, String rating, Pageable pageable) {
        return records.search(cleanFilter(query), cleanFilter(industryBelt), cleanFilter(rating), pageable)
                .map(SupplierRecordView::from);
    }

    @Transactional
    public SupplierRecordView create(SupplierRecordInput input, String actor) {
        var now = Instant.now();
        var row = new SupplierRecord();
        row.id = UUID.randomUUID();
        row.createdAt = now;
        row.createdBy = actor(actor);
        apply(row, input);
        row.updatedAt = now;
        row.updatedBy = actor(actor);
        return SupplierRecordView.from(records.saveAndFlush(row));
    }

    @Transactional
    public SupplierRecordView update(UUID id, long expectedVersion, SupplierRecordInput input, String actor) {
        var row = records.findById(id).orElseThrow(() -> AppException.notFound("供应商记录不存在"));
        assertVersion(row, expectedVersion);
        apply(row, input);
        row.updatedAt = Instant.now();
        row.updatedBy = actor(actor);
        return SupplierRecordView.from(records.saveAndFlush(row));
    }

    @Transactional
    public SupplierRecordView delete(UUID id, long expectedVersion) {
        var row = records.findById(id).orElseThrow(() -> AppException.notFound("供应商记录不存在"));
        assertVersion(row, expectedVersion);
        var view = SupplierRecordView.from(row);
        var licenseAssetId = row.businessLicenseAssetId;
        records.delete(row);
        records.flush();
        if (licenseAssetId != null) storage.retireUnreferenced(List.of(licenseAssetId));
        return view;
    }

    @Transactional
    public SupplierRecordView uploadBusinessLicense(UUID id, long expectedVersion, byte[] bytes, String filename,
                                                    String actor) {
        var row = records.findById(id).orElseThrow(() -> AppException.notFound("供应商记录不存在"));
        assertVersion(row, expectedVersion);
        var previousAssetId = row.businessLicenseAssetId;
        var asset = storage.storeImage(bytes, filename);
        row.businessLicenseAssetId = asset.id;
        row.updatedAt = Instant.now();
        row.updatedBy = actor(actor);
        var saved = SupplierRecordView.from(records.saveAndFlush(row));
        if (previousAssetId != null && !previousAssetId.equals(asset.id)) {
            storage.retireUnreferenced(List.of(previousAssetId));
        }
        return saved;
    }

    @Transactional
    public SupplierRecordView removeBusinessLicense(UUID id, long expectedVersion, String actor) {
        var row = records.findById(id).orElseThrow(() -> AppException.notFound("供应商记录不存在"));
        assertVersion(row, expectedVersion);
        var previousAssetId = row.businessLicenseAssetId;
        row.businessLicenseAssetId = null;
        row.updatedAt = Instant.now();
        row.updatedBy = actor(actor);
        var saved = SupplierRecordView.from(records.saveAndFlush(row));
        if (previousAssetId != null) storage.retireUnreferenced(List.of(previousAssetId));
        return saved;
    }

    private static void apply(SupplierRecord row, SupplierRecordInput input) {
        row.name = input.name().trim();
        row.industryBelt = clean(input.industryBelt());
        row.bossName = clean(input.bossName());
        row.contactDetails = clean(input.contactDetails());
        row.invoiceType = clean(input.invoiceType());
        row.taxPoint = input.taxPoint();
        row.qualityGrade = clean(input.qualityGrade());
        row.deliveryTerms = clean(input.deliveryTerms());
        row.capacityOrder = clean(input.capacityOrder());
        row.stockingStrategy = clean(input.stockingStrategy());
        row.alternativeInquiry = clean(input.alternativeInquiry());
        row.corporateAccount = clean(input.corporateAccount());
        row.corporateBank = clean(input.corporateBank());
        row.hotProductRecommendation = input.hotProductRecommendation();
        row.freeSample = input.freeSample();
        row.afterSales = clean(input.afterSales());
        row.cooperationScore = input.cooperationScore();
        row.rating = clean(input.rating());
        row.monthlyPurchaseAmount = input.monthlyPurchaseAmount();
        row.notes = clean(input.notes());
        row.suggestion = clean(input.suggestion());
    }

    private static void assertVersion(SupplierRecord row, long expectedVersion) {
        if (row.version != expectedVersion) throw AppException.conflict("供应商记录已被其他用户修改，请刷新后重试");
    }

    private static String actor(String value) {
        var cleaned = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        return cleaned.isEmpty() ? "SYSTEM" : cleaned.substring(0, Math.min(cleaned.length(), 64));
    }

    private static String clean(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        return value.trim();
    }

    private static String cleanFilter(String value) { return value == null ? "" : value.trim(); }
}
