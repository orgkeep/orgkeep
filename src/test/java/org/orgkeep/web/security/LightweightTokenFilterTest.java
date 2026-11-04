package org.orgkeep.web.security;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.oidc.Claim;
import io.quarkus.test.security.oidc.ClaimType;
import io.quarkus.test.security.oidc.OidcSecurity;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/**
 * A lightweight token on the API of Orgkeep: an explicit 401, before the role checks (a 403 would say nothing).
 * <p>
 * @TestSecurity runs the call as the given user and roles, without a real token; @OidcSecurity makes its principal
 * a JWT carrying the given claims, as Quarkus OIDC does with a real bearer token. The endpoint works in the realm of
 * the app (acme in test), created at startup.
 */
@QuarkusTest
class LightweightTokenFilterTest {
    private static final String CATALOGS = "/api/admin/catalogs";

    @Test
    @TestSecurity(user = "owner", roles = "realm-owner")
    @OidcSecurity(claims = @Claim(key = "lightweight", value = "true", type = ClaimType.BOOLEAN))
    void lightweightTokenGets401WithItsReason() {
        given().when().get(CATALOGS)
                .then().statusCode(401)
                .header("WWW-Authenticate", containsString("invalid_token"))
                .body("message", equalTo(LightweightTokenFilter.MESSAGE));
    }

    // even where the roles would not be enough: the reason comes first
    @Test
    @TestSecurity(user = "alice", roles = "org-acme:org-admin")
    @OidcSecurity(claims = @Claim(key = "lightweight", value = "true", type = ClaimType.BOOLEAN))
    void lightweightTokenGets401Not403() {
        given().when().get(CATALOGS)
                .then().statusCode(401);
    }

    // a full token goes through as before
    @Test
    @TestSecurity(user = "owner", roles = "realm-owner")
    @OidcSecurity(claims = @Claim(key = "email", value = "owner@acme.test"))
    void fullTokenGoesThrough() {
        given().when().get(CATALOGS)
                .then().statusCode(200);
    }
}
