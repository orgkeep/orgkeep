package org.orgkeep.components.catalog.product;

import org.keycloak.representations.idm.OrganizationRepresentation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Products assigned to an organisation, listed in the attribute {@value #ATTRIBUTE} of the Keycloak organisation
 * (one value per assignment, "{catalog}/{product}"): a display copy for the organisation list, read for free by
 * the organisation search (briefRepresentation=false). The groups /{org}/catalogs/{catalog}/{product} stay the
 * source of truth for everything else (the catalog/product filter, the org products endpoint, the teams): no
 * admin call reaches them from the organisation list.
 * <p>
 * These methods only change the representation: the caller saves it (KeycloakOrgs.update), under the ORG exclusive
 * lock that ProductServiceImpl.assign and unassign already hold.
 */
public final class AssignedProducts {
    public static final String ATTRIBUTE = "assigned-products";

    private AssignedProducts() {
    }

    /** The value of an assignment, e.g. "prod/billing". */
    public static String value(String catalog, String product) {
        return catalog + "/" + product;
    }

    public static List<String> of(OrganizationRepresentation org) {
        return org.getAttributes() == null ? List.of() : org.getAttributes().getOrDefault(ATTRIBUTE, List.of());
    }

    /** @return true if the value was not listed yet: the representation changed */
    public static boolean add(OrganizationRepresentation org, String value) {
        LinkedHashSet<String> listed = new LinkedHashSet<>(of(org));
        if (!listed.add(value)) {
            return false;
        }
        write(org, new ArrayList<>(listed));
        return true;
    }

    /** @return true if the value was listed: the representation changed */
    public static boolean remove(OrganizationRepresentation org, String value) {
        List<String> listed = new ArrayList<>(of(org));
        if (!listed.remove(value)) {
            return false;
        }
        write(org, listed);
        return true;
    }

    private static void write(OrganizationRepresentation org, List<String> values) {
        if (org.getAttributes() == null) {
            org.setAttributes(new HashMap<>());
        }
        if (values.isEmpty()) {
            org.getAttributes().remove(ATTRIBUTE);
        } else {
            org.getAttributes().put(ATTRIBUTE, values);
        }
    }
}
