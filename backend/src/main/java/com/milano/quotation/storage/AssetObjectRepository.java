package com.milano.quotation.storage;
import org.springframework.data.jpa.repository.JpaRepository;import java.util.Optional;import java.util.UUID;
public interface AssetObjectRepository extends JpaRepository<AssetObject,UUID>{Optional<AssetObject>findBySha256(String sha256);}
