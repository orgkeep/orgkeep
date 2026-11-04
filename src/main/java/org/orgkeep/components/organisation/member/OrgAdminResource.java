package org.orgkeep.components.organisation.member;

import org.orgkeep.api.OrgMemberService;
import org.orgkeep.api.model.AddMemberRequest;
import org.orgkeep.api.model.OrgMember;
import org.orgkeep.web.CurrentRealm;
import org.orgkeep.web.security.OrgPermissions;
import io.quarkus.security.PermissionsAllowed;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import lombok.RequiredArgsConstructor;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponseSchema;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.ResponseStatus;

import java.util.List;

/**
 * Management of an organisation by its own org admins.
 * <p>
 * Every method has an "org" path parameter: OrgPermissions checks that the caller administers that org.
 */
@Path("/organisations/{org}/members")
@PermissionsAllowed(OrgPermissions.ADMINISTER_ORG)
@Tag(name = "Organisation members", description = "Members of an organisation, managed by its org admins")
// added automatically for @RolesAllowed, declared explicitly here for @PermissionsAllowed
// ("SecurityScheme" is the default name of the scheme set by quarkus.smallrye-openapi.security-scheme)
@SecurityRequirement(name = "SecurityScheme")
@RequiredArgsConstructor
public class OrgAdminResource {
    private final OrgMemberService memberService;
    private final CurrentRealm currentRealm;

    @GET
    @Operation(summary = "List the members of the organisation")
    @APIResponse(responseCode = "404", description = "Unknown organisation")
    public List<OrgMember> list(@PathParam("org") String org){
        return memberService.list(currentRealm.name(), org);
    }

    @POST
    @ResponseStatus(201)
    @Operation(summary = "Add a member",
            description = "Reuses the user with this email or creates it, then adds it to the organisation "
                    + "and to its default group user-groups/users (role org-user). First and last name are required, "
                    + "an existing user keeps their own.")
    @APIResponseSchema(value = OrgMember.class, responseCode = "201", responseDescription = "Member added")
    @APIResponse(responseCode = "400", description = "Invalid request")
    @APIResponse(responseCode = "404", description = "Unknown organisation")
    @APIResponse(responseCode = "409", description = "The user is already a member, or the organisation is disabled")
    public OrgMember add(@PathParam("org") String org, @Valid @NotNull AddMemberRequest request){
        return memberService.add(currentRealm.name(), org, request);
    }

    @DELETE
    @Path("/{userId}")
    @Operation(summary = "Remove a member",
            description = "Removes the user from the organisation, its groups and its roles. The user itself is kept.")
    @APIResponse(responseCode = "204", description = "Member removed")
    @APIResponse(responseCode = "404", description = "Unknown organisation or not a member")
    @APIResponse(responseCode = "409", description = "The user is an owner of the organisation")
    public void remove(@PathParam("org") String org, @PathParam("userId") String userId){
        memberService.remove(currentRealm.name(), org, userId);
    }
}
