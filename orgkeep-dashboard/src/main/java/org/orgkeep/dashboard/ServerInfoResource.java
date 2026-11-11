package org.orgkeep.dashboard;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Smallest possible endpoint, used by the front to check the front -> backend round trip.
 * Served on /api/info (see quarkus.rest.path).
 */
@Path("/info")
public class ServerInfoResource {

    // both values come from the pom (artifactId / version) unless overridden in application.properties
    @ConfigProperty(name = "quarkus.application.name")
    String application;

    @ConfigProperty(name = "quarkus.application.version")
    String version;

    @GET
    public ServerInfo info() {
        return new ServerInfo(application, version);
    }

    public record ServerInfo(String application, String version) { }
}
