package org.orgkeep.components.realm.smtp;

import org.orgkeep.api.SmtpService;
import org.orgkeep.api.model.SmtpServer;
import org.orgkeep.api.model.UpdateSmtpRequest;
import org.orgkeep.web.CurrentRealm;
import jakarta.annotation.security.RolesAllowed;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import lombok.RequiredArgsConstructor;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponseSchema;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Email server of the realm, through which Keycloak sends invitations, password resets and email verifications,
 * managed by the realm owner.
 */
@Path("/admin/smtp")
@RolesAllowed("realm-owner")
@Tag(name = "SMTP", description = "Email server of the realm, through which Keycloak sends invitations, password resets and email verifications")
@RequiredArgsConstructor
public class SmtpResource {
    private final SmtpService smtpService;
    private final CurrentRealm currentRealm;

    @GET
    @Operation(summary = "Get the SMTP server of the realm")
    @APIResponseSchema(value = SmtpServer.class, responseCode = "200",
            responseDescription = "The SMTP server, every field null / false when the realm has none")
    public SmtpServer get(){
        return smtpService.get(currentRealm.name());
    }

    @PUT
    @Operation(summary = "Replace the SMTP server of the realm",
            description = "Replaces the settings Orgkeep owns. password absent keeps the stored one; other keys "
                    + "set in the Keycloak console (reply-to, envelope from...) are kept.")
    @APIResponseSchema(value = SmtpServer.class, responseCode = "200", responseDescription = "SMTP server updated")
    @APIResponse(responseCode = "400", description = "Invalid body")
    @APIResponse(responseCode = "409", description = "The SMTP server is set by the configuration of Orgkeep (ORGKEEP_SMTP_HOST)")
    public SmtpServer update(@Valid @NotNull UpdateSmtpRequest request){
        return smtpService.update(currentRealm.name(), request);
    }

    @DELETE
    @Operation(summary = "Remove the SMTP server of the realm",
            description = "Empties the SMTP settings of the realm: Keycloak sends no email any more, invitations are not sent.")
    @APIResponse(responseCode = "204", description = "SMTP server removed, idempotent")
    @APIResponse(responseCode = "409", description = "The SMTP server is set by the configuration of Orgkeep (ORGKEEP_SMTP_HOST)")
    public void delete(){
        smtpService.delete(currentRealm.name());
    }
}
