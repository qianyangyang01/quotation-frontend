package com.milano.quotation.idempotency;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;
public interface IdempotencyRepository extends JpaRepository<IdempotencyRecord,UUID>{Optional<IdempotencyRecord> findByAccountAndOperationAndIdempotencyKey(String account,String operation,String idempotencyKey);}
