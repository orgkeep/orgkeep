package org.orgkeep.api.exceptions;

/**
 * Another change is in progress on the resource: the call is refused (409) instead of waiting for it. The messages
 * are generic on purpose: no name of organisation, team or email.
 */
public class ResourceBusyException extends RuntimeException {
    public static final String MESSAGE = "A change is in progress, try again later";
    public static final String LOOP_MESSAGE = "Some organisations could not be updated because a change was in progress "
            + "on them, repeat the call";

    public ResourceBusyException() {
        super(MESSAGE);
    }

    private ResourceBusyException(String message) {
        super(message);
    }

    /** For a call that updates several organisations, some of which were busy: the same call finishes the job. */
    public static ResourceBusyException inSomeOrganisations() {
        return new ResourceBusyException(LOOP_MESSAGE);
    }
}
