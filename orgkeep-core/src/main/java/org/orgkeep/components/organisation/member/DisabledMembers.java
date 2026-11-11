package org.orgkeep.components.organisation.member;

import org.keycloak.representations.idm.OrganizationRepresentation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The members disabled with an organisation, listed in the attribute {@value #ATTRIBUTE} of the Keycloak organisation
 * (user ids, one per value), to enable again only them when the organisation is enabled again. Not on the users: the
 * user profile of Keycloak 24+ drops the user attributes it does not declare.
 * <p>
 * These methods only change the representation: the caller saves it (KeycloakOrgs.update), under the lock of the
 * organisation (OrgLocks).
 */
public final class DisabledMembers {
    public static final String ATTRIBUTE = "disabled-members";

    private DisabledMembers() {
    }

    public static List<String> of(OrganizationRepresentation org) {
        return org.getAttributes() == null ? List.of() : org.getAttributes().getOrDefault(ATTRIBUTE, List.of());
    }

    /** @return true if one of these users was not listed yet: the representation changed */
    public static boolean add(OrganizationRepresentation org, Collection<String> userIds) {
        Set<String> listed = new LinkedHashSet<>(of(org));
        if (!listed.addAll(userIds)) {
            return false;
        }
        write(org, new ArrayList<>(listed));
        return true;
    }

    /** @return true if one of these users was listed: the representation changed */
    public static boolean remove(OrganizationRepresentation org, Collection<String> userIds) {
        List<String> listed = new ArrayList<>(of(org));
        if (!listed.removeAll(userIds)) {
            return false;
        }
        write(org, listed);
        return true;
    }

    private static void write(OrganizationRepresentation org, List<String> userIds) {
        if (org.getAttributes() == null) {
            org.setAttributes(new HashMap<>());
        }
        if (userIds.isEmpty()) {
            org.getAttributes().remove(ATTRIBUTE);
        } else {
            org.getAttributes().put(ATTRIBUTE, userIds);
        }
    }
}
