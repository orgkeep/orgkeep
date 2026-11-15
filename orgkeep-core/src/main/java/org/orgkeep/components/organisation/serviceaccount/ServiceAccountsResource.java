package org.orgkeep.components.organisation.serviceaccount;

import io.quarkus.security.PermissionsAllowed;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import lombok.RequiredArgsConstructor;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponseSchema;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.ResponseStatus;
import org.orgkeep.api.ServiceAccountService;
import org.orgkeep.api.model.Slug;
import org.orgkeep.api.model.CreateServiceAccountRequest;
import org.orgkeep.api.model.ServiceAccount;
import org.orgkeep.api.model.ServiceAccountCreated;
import org.orgkeep.api.model.ServiceAccountCredentials;
import org.orgkeep.api.model.UpdateServiceAccountRequest;
import org.orgkeep.api.model.UpdateServiceAccountStatusRequest;
import org.orgkeep.web.CurrentRealm;
import org.orgkeep.web.security.OrgPermissions;

import java.util.List;

/**
 * Service accounts of an organisation, managed by its own org admins (owners included) and by the realm owner.
 * <p>
 * Every method has an "org" path parameter: OrgPermissions checks that the caller administers that org. The token of
 * a service account carries no org role: it can never call these endpoints.
 */
@Path("/organisations/{org}/service-accounts")
@PermissionsAllowed(OrgPermissions.ADMINISTER_ORG)
@Tag(name = "Organisation service accounts",
        description = "Service accounts (client credentials) of an organisation, managed by its org admins")
@SecurityRequirement(name = "SecurityScheme")
@RequiredArgsConstructor
public class ServiceAccountsResource {
    private final ServiceAccountService serviceAccountService;
    private final CurrentRealm currentRealm;

    @POST
    @ResponseStatus(201)
    @Operation(summary = "Create a service account",
            description = "The secret is in this answer only, with the token URL. Give it again with POST .../secret.")
    @APIResponseSchema(value = ServiceAccountCreated.class, responseCode = "201", responseDescription = "Service account created")
    @APIResponse(responseCode = "400", description = "Invalid name, or a catalog that is unknown or has no product of the organisation")
    @APIResponse(responseCode = "404", description = "Unknown organisation")
    @APIResponse(responseCode = "409", description = "The name is taken, the organisation is disabled, "
            + "or a change is in progress on the organisation")
    public ServiceAccountCreated create(@PathParam("org") @Slug String org, @Valid @NotNull CreateServiceAccountRequest request) {
        return serviceAccountService.create(currentRealm.name(), org, request);
    }

    @GET
    @Operation(summary = "List the service accounts of the organisation", description = "Sorted by name. Never with a secret.")
    @APIResponse(responseCode = "404", description = "Unknown organisation")
    public List<ServiceAccount> list(@PathParam("org") @Slug String org) {
        return serviceAccountService.list(currentRealm.name(), org);
    }

    @GET
    @Path("/{sa}")
    @Operation(summary = "Get a service account")
    @APIResponse(responseCode = "400", description = "Invalid name")
    @APIResponse(responseCode = "404", description = "Unknown organisation or service account")
    public ServiceAccount get(@PathParam("org") @Slug String org, @PathParam("sa") @Slug String sa) {
        return serviceAccountService.get(currentRealm.name(), org, sa);
    }

    @PUT
    @Path("/{sa}")
    @Operation(summary = "Update a service account",
            description = "Replaces the description and the default catalogs. A catalog leaving the list becomes optional again.")
    @APIResponse(responseCode = "400", description = "Invalid name, or a catalog that is unknown or has no product of the organisation")
    @APIResponse(responseCode = "404", description = "Unknown organisation or service account")
    @APIResponse(responseCode = "409", description = "A change is in progress on the organisation or the service account")
    public ServiceAccount update(@PathParam("org") @Slug String org, @PathParam("sa") @Slug String sa,
                                 @Valid @NotNull UpdateServiceAccountRequest request) {
        return serviceAccountService.update(currentRealm.name(), org, sa, request);
    }

    @POST
    @Path("/{sa}/secret")
    @Operation(summary = "Rotate the secret",
            description = "The old secret stops working at once. The new one is in this answer only.")
    @APIResponse(responseCode = "400", description = "Invalid name")
    @APIResponse(responseCode = "404", description = "Unknown organisation or service account")
    @APIResponse(responseCode = "409", description = "A change is in progress on the organisation or the service account")
    public ServiceAccountCredentials regenerateSecret(@PathParam("org") @Slug String org, @PathParam("sa") @Slug String sa) {
        return serviceAccountService.regenerateSecret(currentRealm.name(), org, sa);
    }

    @PUT
    @Path("/{sa}/enabled")
    @Operation(summary = "Enable or disable a service account",
            description = "Disabled: no new token, the sessions are closed and an API that introspects the tokens "
                    + "issued before sees them inactive for good (also after enabling again). An API that only checks the "
                    + "signature of a JWT accepts it until it expires.")
    @APIResponse(responseCode = "400", description = "Invalid name")
    @APIResponse(responseCode = "404", description = "Unknown organisation or service account")
    @APIResponse(responseCode = "409", description = "The organisation is disabled or being enabled again, "
            + "or a change is in progress on the organisation or the service account")
    public ServiceAccount setEnabled(@PathParam("org") @Slug String org, @PathParam("sa") @Slug String sa,
                                     @Valid @NotNull UpdateServiceAccountStatusRequest request) {
        return serviceAccountService.setEnabled(currentRealm.name(), org, sa, request.enabled());
    }

    @DELETE
    @Path("/{sa}")
    @Operation(summary = "Delete a service account", description = "Its credentials stop working at once.")
    @APIResponse(responseCode = "204", description = "Service account deleted")
    @APIResponse(responseCode = "400", description = "Invalid name")
    @APIResponse(responseCode = "404", description = "Unknown organisation or service account")
    @APIResponse(responseCode = "409", description = "A change is in progress on the organisation")
    public void delete(@PathParam("org") @Slug String org, @PathParam("sa") @Slug String sa) {
        serviceAccountService.delete(currentRealm.name(), org, sa);
    }
}
