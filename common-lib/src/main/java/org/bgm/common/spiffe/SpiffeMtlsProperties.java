package org.bgm.common.spiffe;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ADR-0002: SPIFFE mTLS is entirely opt-in and off by default
 * (spiffe.mtls.enabled=false) so services running outside K8s (no SPIRE
 * agent/CSI-mounted Workload API socket) never construct the SPIFFE
 * beans, leaving their existing behavior unchanged.
 *
 * RECONSTRUCTED: this file was rebuilt from usage elsewhere in the
 * codebase (properties.getWorkloadApiSocketPath() in
 * SpiffeMtlsAutoConfiguration) after being lost from the worktree —
 * see conversation for context. workloadApiSocketPath's default matches
 * the CSI volume mount path used by every deployment in k8s/base
 * (csi.spiffe.io driver mounted at /spiffe-workload-api); verify this
 * default against the original source if it turns up.
 */
@ConfigurationProperties(prefix = "spiffe.mtls")
public class SpiffeMtlsProperties {

    private boolean enabled = false;

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
