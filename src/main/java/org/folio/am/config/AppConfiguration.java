package org.folio.am.config;

import org.folio.am.config.properties.ModuleBootstrapCacheProperties;
import org.folio.common.service.TransactionHelper;
import org.folio.security.EnableMgrSecurity;
import org.folio.spring.cql.JpaCqlConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration
@EnableConfigurationProperties(ModuleBootstrapCacheProperties.class)
@EnableMgrSecurity
@Import({JpaCqlConfiguration.class, TransactionHelper.class})
public class AppConfiguration {
}
