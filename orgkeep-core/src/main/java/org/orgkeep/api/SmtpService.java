package org.orgkeep.api;

import org.orgkeep.api.exceptions.OperationNotAllowedException;
import org.orgkeep.api.model.SmtpServer;
import org.orgkeep.api.model.UpdateSmtpRequest;

/**
 * SMTP server of the realm, through which Keycloak sends every email of the realm, managed by the realm owner.
 */
public interface SmtpService {
    /** Never fails for a realm without server: every field null / false. */
    SmtpServer get(String realm);

    /** @throws OperationNotAllowedException if the configuration sets the SMTP server (orgkeep.smtp.host, ON_PREM) */
    SmtpServer update(String realm, UpdateSmtpRequest request);

    /**
     * Empties the SMTP settings of the realm: Keycloak sends no email any more. Idempotent.
     *
     * @throws OperationNotAllowedException if the configuration sets the SMTP server (orgkeep.smtp.host, ON_PREM)
     */
    void delete(String realm);
}
