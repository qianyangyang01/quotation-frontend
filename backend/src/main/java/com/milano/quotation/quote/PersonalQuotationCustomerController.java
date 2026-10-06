package com.milano.quotation.quote;

import com.milano.quotation.audit.AuditService;
import com.milano.quotation.common.ApiResponse;
import com.milano.quotation.common.AppException;
import com.milano.quotation.idempotency.IdempotencyService;
import com.milano.quotation.security.QuotationPrincipal;
import jakarta.persistence.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.*;

/** Name shortcuts only. Never links to finance customers or mutates quotation snapshots. */
@RestController
@RequestMapping("/api/v1/personal-quotation-customers")
@PreAuthorize("hasAuthority('PERM_quote')")
public class PersonalQuotationCustomerController {
    private final PersonalQuotationCustomerRepository rows;
    private final JdbcClient jdbc;
    private final AuditService audit;
    private final IdempotencyService idempotency;
    public PersonalQuotationCustomerController(PersonalQuotationCustomerRepository rows, JdbcClient jdbc, AuditService audit, IdempotencyService idempotency) {
        this.rows=rows; this.jdbc=jdbc; this.audit=audit; this.idempotency=idempotency;
    }
    record View(UUID id, String name, long _version, Instant lastUsedAt) {}
    private View view(PersonalQuotationCustomerEntity row) { return new View(row.id,row.name,row.version,row.lastUsedAt); }
    @GetMapping @Transactional(readOnly=true)
    ApiResponse<List<View>> list(Authentication auth) {
        return ApiResponse.ok(rows.findByUserId(actor(auth).id()).stream()
            .sorted(Comparator.comparing((PersonalQuotationCustomerEntity row)->row.lastUsedAt,Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(row->row.updatedAt,Comparator.reverseOrder()).thenComparing(row->row.id))
            .map(this::view).toList());
    }
    @PostMapping @Transactional
    ApiResponse<tools.jackson.databind.JsonNode> create(@RequestBody ObjectNode body,@RequestHeader("Idempotency-Key") String key,Authentication auth) {
        var actor=actor(auth);lock(actor);var name=name(body,Set.of("name"));
        var cached=idempotency.existing(actor.account(),"personal-customer-create",key,body);
        if(cached.isPresent())return ApiResponse.ok(cached.get());
        duplicate(actor.id(),name,null);
        var row=new PersonalQuotationCustomerEntity();row.id=UUID.randomUUID();row.userId=actor.id();row.name=name;row.normalizedName=normalized(name);row.updatedAt=Instant.now();rows.saveAndFlush(row);
        var result=body.objectNode().put("id",row.id.toString()).put("name",row.name).put("_version",row.version);result.putNull("lastUsedAt");
        idempotency.save(actor.account(),"personal-customer-create",key,body,result);
        audit.record("personal-customer.create","personal-quotation-customer",row.id.toString(),"success",Map.of("name",name));
        return ApiResponse.ok(result);
    }
    @PutMapping("/{id}") @Transactional
    ApiResponse<View> rename(@PathVariable UUID id,@RequestBody ObjectNode body,Authentication auth) {
        var actor=actor(auth);lock(actor);var name=name(body,Set.of("name","_version"));var row=owned(id,actor);
        if(!body.path("_version").isIntegralNumber()||body.path("_version").asLong(-1)!=row.version)throw conflict();
        duplicate(actor.id(),name,id);var before=row.name;
        row.name=name;row.normalizedName=normalized(name);row.updatedAt=Instant.now();rows.saveAndFlush(row);
        audit.record("personal-customer.rename","personal-quotation-customer",id.toString(),"success",Map.of("before",before,"after",name));
        return ApiResponse.ok(view(row));
    }
    @DeleteMapping("/{id}") @Transactional
    ApiResponse<Void> remove(@PathVariable UUID id,@RequestHeader("If-Match") long version,Authentication auth) {
        var actor=actor(auth);lock(actor);var row=owned(id,actor);if(version!=row.version)throw conflict();
        rows.delete(row);rows.flush();
        audit.record("personal-customer.remove","personal-quotation-customer",id.toString(),"success",Map.of("name",row.name));
        return ApiResponse.ok(null);
    }
    @PostMapping("/{id}/use") @Transactional
    ApiResponse<View> use(@PathVariable UUID id,Authentication auth) {
        var actor=actor(auth);lock(actor);var row=owned(id,actor);row.lastUsedAt=Instant.now();rows.saveAndFlush(row);return ApiResponse.ok(view(row));
    }
    private void lock(QuotationPrincipal actor) { jdbc.sql("select id from app_user where id=:id for update").param("id",actor.id()).query(UUID.class).single(); }
    private PersonalQuotationCustomerEntity owned(UUID id,QuotationPrincipal actor) {
        return rows.findByIdAndUserId(id,actor.id()).orElseThrow(()->AppException.notFound("个人客户不存在或已移出，请刷新列表"));
    }
    private void duplicate(UUID user,String name,UUID except) {
        if(rows.findByUserIdAndNormalizedName(user,normalized(name)).filter(row->!row.id.equals(except)).isPresent())
            throw AppException.conflict("已存在同名个人客户，请直接选择已有客户");
    }
    private static String name(ObjectNode body,Set<String> fields) {
        body.propertyNames().forEach(key->{if(!fields.contains(key))throw AppException.unprocessable("个人客户只能保存名称");});
        if(!body.path("name").isTextual())throw AppException.unprocessable("请输入客户名称");
        var name=body.path("name").asText().strip();
        if(name.isEmpty()||name.length()>120||name.codePoints().anyMatch(Character::isISOControl))throw AppException.unprocessable("客户名称不能为空、不能含控制字符且最多120字");
        return name;
    }
    private static String normalized(String name){return name.toLowerCase(Locale.ROOT);}
    private static AppException conflict(){return AppException.conflict("个人客户已在其他页面更新，请刷新后重试");}
    private static QuotationPrincipal actor(Authentication auth){return (QuotationPrincipal)auth.getPrincipal();}
}

@Entity @Table(name="personal_quotation_customer",uniqueConstraints=@UniqueConstraint(columnNames={"user_id","normalized_name"}))
class PersonalQuotationCustomerEntity {
    @Id UUID id;
    @Column(name="user_id",nullable=false) UUID userId;
    @Column(nullable=false,length=120) String name;
    @Column(name="normalized_name",nullable=false,length=240) String normalizedName;
    @Column(name="updated_at",nullable=false) Instant updatedAt;
    @Column(name="last_used_at") Instant lastUsedAt;
    @Version long version;
}
interface PersonalQuotationCustomerRepository extends JpaRepository<PersonalQuotationCustomerEntity,UUID> {
    List<PersonalQuotationCustomerEntity> findByUserId(UUID userId);
    Optional<PersonalQuotationCustomerEntity> findByIdAndUserId(UUID id,UUID userId);
    Optional<PersonalQuotationCustomerEntity> findByUserIdAndNormalizedName(UUID userId,String normalizedName);
}
