package com.milano.quotation.logistics;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;import java.util.Optional;import java.util.UUID;
interface LogisticsProviderRepository extends JpaRepository<LogisticsProviderEntity,UUID>{boolean existsByCodeIgnoreCase(String code);Optional<LogisticsProviderEntity> findByCodeIgnoreCase(String code);List<LogisticsProviderEntity> findAllByOrderByUpdatedAtDesc();}
interface LogisticsChannelRepository extends JpaRepository<LogisticsChannelEntity,UUID>{boolean existsByCodeIgnoreCase(String code);Optional<LogisticsChannelEntity> findByCodeIgnoreCase(String code);long countByProviderId(UUID providerId);long countByCurrentVersionIdIsNotNull();List<LogisticsChannelEntity> findAllByOrderByUpdatedAtDesc();List<LogisticsChannelEntity> findByProviderIdOrderByUpdatedAtDesc(UUID providerId);}
interface LogisticsVersionRepository extends JpaRepository<LogisticsVersionEntity,UUID>{List<LogisticsVersionEntity> findAllByOrderByCreatedAtDesc();List<LogisticsVersionEntity> findByChannelIdOrderByVersionNumberDesc(UUID channelId);Optional<LogisticsVersionEntity> findByChannelIdAndSourceHash(UUID channelId,String sourceHash);}
