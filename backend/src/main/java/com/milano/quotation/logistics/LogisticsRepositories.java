package com.milano.quotation.logistics;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface LogisticsProviderRepository extends JpaRepository<LogisticsProviderEntity, UUID> {
    @Query(value="select exists(select 1 from logistics_provider where dataset_id=logistics_active_dataset() and lower(code)=lower(:code))",nativeQuery=true)
    boolean existsByCodeIgnoreCase(String code);
    @Query(value="select * from logistics_provider where dataset_id=logistics_active_dataset() and lower(code)=lower(:code)",nativeQuery=true)
    Optional<LogisticsProviderEntity> findByCodeIgnoreCase(String code);
    @Query(value="select * from logistics_provider where dataset_id=logistics_active_dataset() order by updated_at desc",nativeQuery=true)
    List<LogisticsProviderEntity> findAllByOrderByUpdatedAtDesc();
}
interface LogisticsChannelRepository extends JpaRepository<LogisticsChannelEntity, UUID> {
    @Query(value="select exists(select 1 from logistics_channel where dataset_id=logistics_active_dataset() and lower(code)=lower(:code))",nativeQuery=true)
    boolean existsByCodeIgnoreCase(String code);
    @Query(value="select * from logistics_channel where dataset_id=logistics_active_dataset() and lower(code)=lower(:code)",nativeQuery=true)
    Optional<LogisticsChannelEntity> findByCodeIgnoreCase(String code);
    long countByProviderId(UUID providerId);
    @Query(value="select count(*) from logistics_channel c join logistics_provider p on p.id=c.provider_id join logistics_version v on v.id=c.current_version_id and v.status='published' where logistics_version_quote_ready(v.id) and c.dataset_id=logistics_active_dataset() and c.current_version_id is not null and c.archived_at is null and coalesce((c.payload->>'enabled')::boolean,true) and coalesce((p.payload->>'enabled')::boolean,true)",nativeQuery=true)
    long countByCurrentVersionIdIsNotNullAndArchivedAtIsNull();
    @Query(value="select * from logistics_channel where dataset_id=logistics_active_dataset() order by updated_at desc",nativeQuery=true)
    List<LogisticsChannelEntity> findAllByOrderByUpdatedAtDesc();
    @Query(value="select * from logistics_channel where dataset_id=logistics_active_dataset() and provider_id=:providerId order by updated_at desc",nativeQuery=true)
    List<LogisticsChannelEntity> findByProviderIdOrderByUpdatedAtDesc(UUID providerId);
}
interface LogisticsVersionRepository extends JpaRepository<LogisticsVersionEntity, UUID> {
    @Query(value="select v.* from logistics_version v join logistics_channel c on c.id=v.channel_id where c.dataset_id=logistics_active_dataset() order by v.created_at desc",nativeQuery=true)
    List<LogisticsVersionEntity> findAllByOrderByCreatedAtDesc();
    List<LogisticsVersionEntity> findByChannelIdOrderByVersionNumberDesc(UUID channelId);
    Optional<LogisticsVersionEntity> findByChannelIdAndSourceHash(UUID channelId,String sourceHash);
}
