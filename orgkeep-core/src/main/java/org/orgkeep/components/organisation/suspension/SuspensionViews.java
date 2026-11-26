package org.orgkeep.components.organisation.suspension;

import org.orgkeep.api.model.OrgMember;
import org.orgkeep.api.model.ServiceAccount;
import org.orgkeep.api.model.Suspension;

/**
 * Hides {@code suspendedBy} from an org admin: the reason and the date are shown to org admins too, but who
 * suspended an account is for the realm owner only. Used by {@code OrgAdminResource} and
 * {@code ServiceAccountsResource}; the realm owner's own endpoints ({@code SuspensionsResource}) always see it.
 */
public final class SuspensionViews {
    private SuspensionViews() {
    }

    public static OrgMember forCaller(OrgMember member, boolean realmOwner) {
        if (realmOwner || member.suspension() == null) {
            return member;
        }
        return new OrgMember(member.id(), member.username(), member.email(), member.firstName(), member.lastName(),
                member.enabled(), member.role(), member.invitationPending(), hide(member.suspension()));
    }

    public static ServiceAccount forCaller(ServiceAccount serviceAccount, boolean realmOwner) {
        if (realmOwner || serviceAccount.suspension() == null) {
            return serviceAccount;
        }
        return new ServiceAccount(serviceAccount.name(), serviceAccount.clientId(), serviceAccount.userId(),
                serviceAccount.description(), serviceAccount.enabled(), serviceAccount.defaultCatalogs(),
                serviceAccount.teams(), hide(serviceAccount.suspension()));
    }

    private static Suspension hide(Suspension suspension) {
        return new Suspension(suspension.reason(), suspension.suspendedAt(), null);
    }
}
