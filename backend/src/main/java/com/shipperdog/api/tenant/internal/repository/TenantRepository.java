package com.shipperdog.api.tenant.internal.repository;

import com.shipperdog.api.tenant.internal.entity.TenantEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantRepository extends JpaRepository<TenantEntity, UUID> {}
