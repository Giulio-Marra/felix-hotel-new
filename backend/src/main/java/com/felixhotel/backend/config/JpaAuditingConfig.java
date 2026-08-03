package com.felixhotel.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * Abilita il popolamento automatico di created_at/updated_at (vedi
 * {@code BaseAuditableEntity}) tramite Spring Data JPA Auditing.
 */
@Configuration
@EnableJpaAuditing
public class JpaAuditingConfig {
}
