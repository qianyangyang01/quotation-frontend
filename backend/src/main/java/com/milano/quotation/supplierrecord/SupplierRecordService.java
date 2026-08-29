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
        validateDeliveryTransition(input.deliveryTerms(), null);
        apply(row, input);
        row.updatedAt = now;
        row.updatedBy = actor(actor);
        return SupplierRecordView.from(records.saveAndFlush(row));
    }

    @Transactional
    public SupplierRecordView update(UUID id, long expectedVersion, SupplierRecordInput input, String actor) {
        var row = records.findById(id).orElseThrow(() -> AppException.notFound("供应商记录不存在"));
        assertVersion(row, expectedVersion);
        validateDeliveryTransition(input.deliveryTerms(), row.deliveryTerms);
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
        row.invoiceType = normalizeInvoiceType(input.invoiceType());
        validateInvoice(row.invoiceType, input.taxPoint());
        row.taxPoint = "没票".equals(row.invoiceType) ? null : input.taxPoint();
        row.qualityGrade = clean(input.qualityGrade());
        row.deliveryTerms = clean(input.deliveryTerms());
        row.capacityOrder = clean(input.capacityOrder());
        row.stockingStrategy = clean(input.stockingStrategy());
        row.alternativeInquiry = clean(input.alternativeInquiry());
        row.corporateAccount = clean(input.corporateAccount());
        row.corporateBank = clean(input.corporateBank());
        row.hotProductRecommendation = input.hotProductRecommendation();
        row.freeSample = input.freeSample();
        // afterSales and cooperationScore are legacy free-text/manual-score fields.
        // Keep accepting them in the request for compatibility, but never overwrite history.
        row.priceLevel = clean(input.priceLevel());
        row.afterSalesAvailable = input.afterSalesAvailable();
        row.rating = clean(input.rating());
        row.monthlyPurchaseAmount = input.monthlyPurchaseAmount();
        row.notes = clean(input.notes());
        row.suggestion = clean(input.suggestion());
        var score = SupplierRecordScoring.evaluate(row);
        row.calculatedScore = score.total();
        row.scorePolicyVersion = score.policyVersion();
    }

    private static String normalizeInvoiceType(String value) {
        var cleaned = clean(value);
        if (cleaned == null) return null;
        var normalized = SupplierRecordScoring.normalizeInvoiceType(cleaned);
        if (!List.of("专票", "普票", "没票").contains(normalized)) {
            throw AppException.unprocessable("开票类型只能选择专票、普票或没票");
        }
        return normalized;
    }

    private static void validateInvoice(String invoiceType, java.math.BigDecimal taxPoint) {
        if (("专票".equals(invoiceType) || "普票".equals(invoiceType)) && taxPoint == null) {
            throw AppException.unprocessable("选择专票或普票后必须填写票点百分比");
        }
    }

    private static void validateDeliveryTransition(String requestedValue, String persistedValue) {
        var requested = clean(requestedValue);
        var persisted = clean(persistedValue);
        var persistedIsLegacy = persisted != null && !isDeliveryOption(persisted);
        if (requested == null) {
            if (persistedIsLegacy) {
                throw AppException.unprocessable("历史交期记录不能直接清空，请选择新的交期选项");
            }
            return;
        }
        if (isDeliveryOption(requested)) return;
        if (persisted != null && persisted.equals(requested)) return;
        throw AppException.unprocessable("交期只能选择0天、1天、7天内或7天以上；历史交期记录只能原样保留或替换为新选项");
    }

    private static boolean isDeliveryOption(String value) {
        return "0".equals(value) || "1".equals(value) || "7".equals(value) || "8".equals(value);
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
