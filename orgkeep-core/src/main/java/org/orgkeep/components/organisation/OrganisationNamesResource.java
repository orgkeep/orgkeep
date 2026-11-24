package org.orgkeep.components.organisation;

import org.orgkeep.api.OrgService;
import org.orgkeep.web.CurrentRealm;
import jakarta.annotation.security.RolesAllowed;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import lombok.RequiredArgsConstructor;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;

/**
 * Names of the organisations of the realm, for a picker. A separate class and path, not under
 * /admin/organisations/: "names" would collide with an organisation named "names".
 */
@Path("/admin/organisation-names")
@RolesAllowed("realm-owner")
@Tag(name = "Organisations", description = "Organisations of the realm, managed by the realm owner")
@RequiredArgsConstructor
public class OrganisationNamesResource {
    private final OrgService orgService;
    private final CurrentRealm currentRealm;

    @GET
    @Operation(summary = "List organisation names", description = "For a picker: no total, one Keycloak call.")
    public List<String> list(@QueryParam("search") String search,
                             @QueryParam("max") @DefaultValue("20") @Min(1) @Max(100) int max){
        return orgService.names(currentRealm.name(), search, max);
    }
}
