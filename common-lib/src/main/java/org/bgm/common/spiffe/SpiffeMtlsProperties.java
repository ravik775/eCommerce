package org.bgm.common.spiffe;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ADR-0002: configuration for the SPIFFE-backed mTLS wiring. Disabled by
 * default (spiffe.mtls.enabled=false) so services running outside K8s
 * (Docker Compose, no SPIRE agent/CSI-driver-mounted socket) are
 * completely unaffected — this is opt-in per environment, not a change
 * to every service's default behavior.
 */
@ConfigurationProperties(prefix = "spiffe.mtls")
public class SpiffeMtlsProperties {

    /** Off by default — see class Javadoc. */
    private boolean enabled = false;

    /**
     * Unix domain socket path for the Workload API, as mounted by the
     * SPIFFE CSI Driver's ephemeral volume into this pod (see
     * k8s/base/spire/csi-driver.yaml and each app Deployment's volume
     * mount, added when this service is wired for mTLS).
     */
    // File name is "agent.sock", not "spire-agent.sock" — matches the
    // SPIRE agent's own socket path (k8s/base/spire/agent.yaml's
    // healthcheck: /run/spire/sockets/agent.sock), which the CSI driver
    // re-exposes into this pod under the mount dir below unchanged.
    // Wrong name caused a live FileNotFoundException on first deploy.
    private String workloadApiSocketPath = "unix:///spiffe-workload-api/agent.sock";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getWorkloadApiSocketPath() {
        return workloadApiSocketPath;
    }

    public void setWorkloadApiSocketPath(String workloadApiSocketPath) {
        this.workloadApiSocketPath = workloadApiSocketPath;
    }
}
