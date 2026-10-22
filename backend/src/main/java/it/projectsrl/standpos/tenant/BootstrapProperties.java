package it.projectsrl.standpos.tenant;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * First tenant, venue and owner, read from {@code STANDPOS_BOOTSTRAP_*} environment variables. Used by the
 * {@code --bootstrap} command and, with {@code onStartup}, by the dev profile.
 */
@ConfigurationProperties("standpos.bootstrap")
public record BootstrapProperties(
        boolean onStartup,
        String tenantName,
        String venueName,
        String ownerName,
        String ownerEmail,
        String ownerPassword,
        String ownerPin) {}
