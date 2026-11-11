package org.orgkeep.fixtures;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The flat claims of a catalog in a token: one top-level claim per product and per metered feature,
 * "prod.billing" and "prod.billing.search". There is no "prod" object to read them from at once.
 */
public final class CatalogClaims {

    private CatalogClaims() {
    }

    /**
     * The top-level claims whose name starts with the prefix and a dot, e.g. "prod" for a catalog, "prod.billing" for
     * the metered features of a product. Empty if there is none.
     */
    public static ObjectNode of(JsonNode claims, String prefix) {
        ObjectNode found = JsonNodeFactory.instance.objectNode();
        claims.properties().forEach(claim -> {
            if (claim.getKey().startsWith(prefix + ".")) {
                found.set(claim.getKey(), claim.getValue());
            }
        });
        return found;
    }
}
