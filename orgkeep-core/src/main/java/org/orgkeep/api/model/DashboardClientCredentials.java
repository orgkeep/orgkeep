package org.orgkeep.api.model;

/** Client id and secret of the dashboard client. The secret is masked by {@link #toString()}. */
public record DashboardClientCredentials(String clientId, String secret) {
    @Override
    public String toString() {
        return "DashboardClientCredentials[clientId=" + clientId + ", secret=****]";
    }
}
