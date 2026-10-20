package io.orgkeep.components.organisation.usergroup;

import io.orgkeep.api.UserGroupService;
import io.orgkeep.api.model.CreateUserGroupRequest;
import io.orgkeep.api.model.UserGroup;
import io.orgkeep.web.CurrentRealm;
import io.orgkeep.web.security.OrgPermissions;
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

import java.util.List;

/**
 * User groups of an organisation, managed by its own org admins (owners included) and by the realm owner.
 * <p>
 * Every method has an "org" path parameter: OrgPermissions checks that the caller administers that org.
 */
@Path("/organisations/{org}/user-groups")
@PermissionsAllowed(OrgPermissions.ADMINISTER_ORG)
@Tag(name = "Organisation user groups", description = "User groups of an organisation, managed by its org admins")
@SecurityRequirement(name = "SecurityScheme")
@RequiredArgsConstructor
public class UserGroupsResource {
    private final UserGroupService userGroupService;
    private final CurrentRealm currentRealm;

    @GET
    @Operation(summary = "List the user groups of the organisation", description = "With their members.")
    @APIResponse(responseCode = "404", description = "Unknown organisation")
    public List<UserGroup> list(@PathParam("org") String org){
        return userGroupService.list(currentRealm.name(), org);
    }

    @POST
    @ResponseStatus(201)
    @Operation(summary = "Create a user group", description = "An empty group, granting no product.")
    @APIResponseSchema(value = UserGroup.class, responseCode = "201", responseDescription = "User group created")
    @APIResponse(responseCode = "400", description = "Invalid request")
    @APIResponse(responseCode = "404", description = "Unknown organisation")
    @APIResponse(responseCode = "409", description = "The organisation has a user group with this name")
    public UserGroup create(@PathParam("org") String org, @Valid @NotNull CreateUserGroupRequest request){
        return userGroupService.create(currentRealm.name(), org, request);
    }

    @DELETE
    @Path("/{group}")
    @Operation(summary = "Delete a user group", description = "Its members lose the products it granted. "
            + "The groups admin and users cannot be deleted.")
    @APIResponse(responseCode = "204", description = "User group deleted")
    @APIResponse(responseCode = "404", description = "Unknown organisation or user group")
    @APIResponse(responseCode = "409", description = "Predefined group (admin, users)")
    public void delete(@PathParam("org") String org, @PathParam("group") String group){
        userGroupService.delete(currentRealm.name(), org, group);
    }

    // PUT on the membership itself: putting a member twice in a group is not an error
    @PUT
    @Path("/{group}/members/{userId}")
    @Operation(summary = "Put a member in a user group",
            description = "The member gets the products of the group. In the group admin, the member becomes org admin.")
    @APIResponse(responseCode = "204", description = "Member in the group")
    @APIResponse(responseCode = "404", description = "Unknown organisation or user group, or not a member of the organisation")
    public void addMember(@PathParam("org") String org, @PathParam("group") String group,
                          @PathParam("userId") String userId){
        userGroupService.addMember(currentRealm.name(), org, group, userId);
    }

    @DELETE
    @Path("/{group}/members/{userId}")
    @Operation(summary = "Take a member out of a user group",
            description = "The member loses the products of the group, unless another group grants them.")
    @APIResponse(responseCode = "204", description = "Member out of the group")
    @APIResponse(responseCode = "404", description = "Unknown organisation or user group")
    @APIResponse(responseCode = "409", description = "Group users: every member stays in it")
    public void removeMember(@PathParam("org") String org, @PathParam("group") String group,
                             @PathParam("userId") String userId){
        userGroupService.removeMember(currentRealm.name(), org, group, userId);
    }
}
