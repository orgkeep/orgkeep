package io.orgkeep.dashboard.core;

import io.orgkeep.dashboard.organisation.OrgMember;
import io.quarkus.oidc.token.propagation.common.AccessToken;
import io.quarkus.rest.client.reactive.ClientExceptionMapper;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import java.util.List;

/**
 * The members of an organisation (OrgAdminResource in orgkeep-core). A separate client: the core serves them
 * under /organisations/{org}, the path of the org admins, which the realm owner may call too.
 * Same setup as {@link OrganisationsClient}.
 */
@Path("/organisations/{org}/members")
@RegisterRestClient(configKey = "core")
@AccessToken
public interface OrgMembersClient {

    @GET
    List<OrgMember> list(@PathParam("org") String org);

    @ClientExceptionMapper
    static RuntimeException toException(Response response) {
        return CoreApiException.from(response);
    }
}
