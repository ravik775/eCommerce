package org.bgm.common.spiffe;

import io.spiffe.exception.SocketEndpointAddressException;
import io.spiffe.workloadapi.DefaultWorkloadApiClient;
import io.spiffe.workloadapi.Watcher;
import io.spiffe.workloadapi.WorkloadApiClient;
import io.spiffe.workloadapi.X509Context;
import io.spiffe.workloadapi.X509Source;
import org.apache.catalina.connector.Connector;
import org.apache.coyote.http11.AbstractHttp11JsseProtocol;
import org.apache.tomcat.util.net.SSLHostConfig;
import org.apache.tomcat.util.net.SSLHostConfigCertificate;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * ADR-0002: enforces inbound mTLS on the embedded Tomcat connector.
 * Config is file-based (SVID/key/trust-bundle PEM files, rewritten
 * periodically by {@link SpiffePemFileWriter}) rather than wiring a
 * pre-built {@link javax.net.ssl.SSLContext} directly into
 * {@code SSLHostConfigCertificate} — that path has documented bugs in
 * some Spring Boot/Tomcat version combinations (the custom SSLContext
 * gets silently ignored; see spring-projects/spring-boot#47326), found
 * live while first implementing this class as a compile-then-verify
 * step, not assumed from the java-spiffe-provider README's example
 * alone. File-based config is the same mechanism SPIRE's own
 * {@code spiffe-helper} sidecar uses.
 * <p>
 * Rotation (ADR-0051, 2026-08-16 update): reloads are event-driven, not
 * polled — a dedicated {@link WorkloadApiClient} registers a
 * {@link Watcher} via {@code watchX509Context}, the same public API the
 * official {@code spiffe-helper} reference sidecar uses (verified by
 * reading its actual source, not assumed), so a rotation is picked up
 * within the Workload API's own push latency rather than up to a fixed
 * poll interval later. A slow periodic reconciliation runs alongside it
 * as a safety net — the same "watch plus periodic resync" pattern
 * Kubernetes controllers use — in case a watch stream dies in a way that
 * doesn't invoke {@code onError} (this class's own earlier polling-only
 * design was itself already found live to silently stop, see
 * {@code rotate()}'s Javadoc below).
 * <p>
 * {@code Connector} is not itself a Spring bean — Tomcat creates it
 * internally, after {@code WebServerFactoryCustomizer} runs — so the
 * customizer below captures the live reference into
 * {@link #liveConnectors} for the rotation task to reach later, rather
 * than (incorrectly) trying to {@code @Autowired} it.
 * <p>
 * Guards on {@link Connector} itself, not {@link TomcatServletWebServerFactory}
 * — the latter is always resolvable (it's a core Spring Boot class,
 * present even on the reactive gateway's classpath), so gating on it let
 * this configuration load on api-gateway and crash the whole app with
 * {@code NoClassDefFoundError: org/apache/catalina/connector/Connector},
 * found live on first deploy — the actually-scarce class (only present
 * when the real Tomcat embed jar is on the classpath, i.e. servlet-based
 * services) is what the guard must reference.
 */
@AutoConfiguration
@ConditionalOnClass(Connector.class)
@ConditionalOnProperty(prefix = "spiffe.mtls", name = "enabled", havingValue = "true")
public class SpiffeInboundMtlsAutoConfiguration {

    private static final Path PEM_DIR = Path.of(System.getProperty("java.io.tmpdir"), "spiffe-svid");
    // ADR-0051 (2026-08-16 update): was the PRIMARY reload trigger at 30s
    // (previously 5 minutes) — replaced by the event-driven Watcher
    // below, which reacts within the Workload API's own push latency
    // instead of waiting for the next tick. This interval now backs only
    // the periodic reconciliation safety net (self-heals if the watch
    // stream dies silently), so it's deliberately looser than the old
    // primary-mechanism value — 2 minutes is still well inside any
    // realistic SVID TTL's safety margin for a reconciliation pass, not
    // the thing standing between "on time" and "expired" anymore.
    private static final long RECONCILE_INTERVAL_SECONDS = 120;
    // If the currently-active SVID has less than this much validity left
    // at poll time, something is already wrong upstream (a healthy
    // Workload API connection would have delivered a new SVID well
    // before this point, since SPIRE agents rotate at roughly half the
    // SVID's TTL) — logged at WARN so it's visible before the cert
    // actually expires, not just after, per this project's "surface a
    // gap before it's found by someone else" pattern (see ADR-0048).
    private static final Duration EXPIRY_WARNING_MARGIN = Duration.ofMinutes(2);

    private final List<Connector> liveConnectors = new CopyOnWriteArrayList<>();

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> spiffeTomcatMtlsCustomizer(X509Source x509Source) {
        return factory -> factory.addConnectorCustomizers(connector -> {
            configureConnectorForMtls(connector, SpiffePemFileWriter.writeCurrentSvid(x509Source, PEM_DIR));
            liveConnectors.add(connector);
        });
    }

    /**
     * Registered as a {@link SmartLifecycle} so it starts after the web
     * server (and therefore the connector customizer above) has run.
     */
    @Bean
    public SmartLifecycle spiffeSvidRotationTask(X509Source x509Source, SpiffeMtlsProperties properties) {
        return new SvidRotationLifecycle(x509Source, liveConnectors, properties);
    }

    private static void configureConnectorForMtls(Connector connector, SpiffePemFileWriter.PemPaths pem) {
        connector.setScheme("https");
        connector.setSecure(true);
        connector.setProperty("SSLEnabled", "true");

        SSLHostConfig sslHostConfig = new SSLHostConfig();
        sslHostConfig.setSslProtocol("TLS");
        // NEED, not WANT: a peer without a valid SPIFFE SVID must be
        // rejected at the TLS layer, not merely left unauthenticated at
        // the application layer — that's the actual zero-trust
        // requirement (ADR-0006), not a softer "prefer but allow" check.
        sslHostConfig.setCertificateVerification("required");
        // NOT setCaCertificateFile(...): that property is OpenSSL-syntax
        // only — under JSSE (this connector's actual mode, no APR/OpenSSL
        // native library present), Tomcat logs a warning and silently
        // ignores it, leaving the JSSE default (near-empty) trust store
        // in place. The connector still *asked* for a client cert
        // (certificateVerification=required forces that unconditionally)
        // so a no-cert connection was correctly rejected either way —
        // masking the bug until a real peer SVID was presented and
        // failed validation with certificate_unknown, found live on the
        // first genuine two-service mTLS call. Fixed by building the
        // trust store as an in-memory KeyStore and setting it directly,
        // which works under both JSSE and OpenSSL syntax.
        sslHostConfig.setTrustStore(buildTrustStore(pem.caCertificateFile()));

        SSLHostConfigCertificate certificate = new SSLHostConfigCertificate(sslHostConfig, SSLHostConfigCertificate.Type.UNDEFINED);
        certificate.setCertificateFile(pem.certificateFile().toString());
        certificate.setCertificateKeyFile(pem.certificateKeyFile().toString());
        certificate.setCertificateChainFile(pem.certificateChainFile().toString());
        sslHostConfig.addCertificate(certificate);

        connector.addSslHostConfig(sslHostConfig);
    }

    private static KeyStore buildTrustStore(Path caCertificateFile) {
        try {
            KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
            trustStore.load(null, null);
            CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
            try (InputStream in = Files.newInputStream(caCertificateFile)) {
                int i = 0;
                for (Certificate certificate : certificateFactory.generateCertificates(in)) {
                    trustStore.setCertificateEntry("spire-ca-" + i++, certificate);
                }
            }
            return trustStore;
        } catch (java.io.IOException e) {
            throw new UncheckedIOException("Unable to read SPIFFE trust bundle", e);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("Unable to build trust store from SPIFFE trust bundle", e);
        }
    }

    private static final class SvidRotationLifecycle implements SmartLifecycle {
        private static final Logger log = LoggerFactory.getLogger(SvidRotationLifecycle.class);

        private final X509Source x509Source;
        private final List<Connector> connectors;
        private final SpiffeMtlsProperties properties;
        private ScheduledExecutorService reconcileExecutor;
        private WorkloadApiClient watchClient;
        private volatile boolean running;

        SvidRotationLifecycle(X509Source x509Source, List<Connector> connectors, SpiffeMtlsProperties properties) {
            this.x509Source = x509Source;
            this.connectors = connectors;
            this.properties = properties;
        }

        @Override
        public void start() {
            // ADR-0051 (2026-08-16 update): a SEPARATE WorkloadApiClient,
            // not the one backing the injected X509Source — X509Source
            // doesn't expose its internal client, and WorkloadApiClient's
            // own watchX509Context(...) is otherwise exactly the public,
            // documented API the official spiffe-helper reference sidecar
            // uses for the same purpose (verified in its actual source,
            // not assumed from a README). Both clients independently
            // receive the same Workload API pushes for this workload, so
            // there's no correctness gap from using two connections —
            // only the trigger differs (a push here vs. X509Source's own
            // internal state update), and rotate() re-reads the shared
            // x509Source bean either way, so both are always in sync by
            // the time rotate() runs.
            try {
                watchClient = DefaultWorkloadApiClient.newClient(
                        DefaultWorkloadApiClient.ClientOptions.builder()
                                .spiffeSocketPath(properties.getWorkloadApiSocketPath())
                                .build());
                watchClient.watchX509Context(new Watcher<X509Context>() {
                    @Override
                    public void onUpdate(X509Context update) {
                        rotate();
                    }

                    @Override
                    public void onError(Throwable error) {
                        // Not rethrown, not retried by hand: the
                        // underlying client already retries the stream
                        // itself with its own ExponentialBackoffPolicy —
                        // same reliance on the library's own mechanism
                        // spiffe-helper uses, rather than hand-rolled
                        // reconnect logic. The reconciliation loop below
                        // is this class's actual safety net for however
                        // long that reconnection takes.
                        log.warn("SPIFFE Workload API watch error — relying on the client's own "
                                + "reconnect and the periodic reconciliation pass in the meantime", error);
                    }
                });
            } catch (SocketEndpointAddressException e) {
                log.error("Unable to start SPIFFE SVID watch — falling back to reconciliation-only reload", e);
            }

            reconcileExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "spiffe-svid-reconcile");
                t.setDaemon(true);
                return t;
            });
            reconcileExecutor.scheduleAtFixedRate(
                    this::rotate, RECONCILE_INTERVAL_SECONDS, RECONCILE_INTERVAL_SECONDS, TimeUnit.SECONDS);
            running = true;
        }

        // Called both from the event-driven Watcher's onUpdate (the
        // common, fast path) and from the periodic reconciliation tick
        // (the safety net for a watch stream that died in a way that
        // didn't invoke onError, or the rare case the watch itself never
        // started at all — see the SocketEndpointAddressException catch
        // above). Idempotent either way: re-writing the same PEM content
        // and reloading is harmless when nothing actually changed.
        private void rotate() {
            // Must not let any exception escape: scheduleAtFixedRate
            // silently and permanently cancels all future executions of a
            // task that throws, with nothing logged anywhere — found live
            // as the root cause of an expired, never-refreshed SVID (7
            // successful 5-minute rotations, then the schedule died with
            // no trace and the cert expired ~24h later). A transient
            // Workload API hiccup here must cost at most one missed
            // rotation, not all of them.
            try {
                checkExpiryMargin();
                SpiffePemFileWriter.writeCurrentSvid(x509Source, PEM_DIR);
                for (Connector connector : connectors) {
                    // reloadSslHostConfigs() lives on the protocol handler
                    // (which delegates to its endpoint), not on Connector
                    // itself — found live via "cannot find symbol" on the
                    // first, more obvious-looking attempt.
                    if (connector.getProtocolHandler() instanceof AbstractHttp11JsseProtocol<?> jsseProtocol) {
                        jsseProtocol.reloadSslHostConfigs();
                    }
                }
            } catch (Exception e) {
                log.error("SPIFFE SVID rotation failed; will retry on the next event or reconciliation pass", e);
            }
        }

        // ADR-0051: this check remains valuable even with event-driven
        // reload — it's specifically about the case a healthy watch
        // can't self-report: the Workload API being down long enough
        // that neither the watch nor the reconciliation pass has
        // anything fresher to give, and X509Source.getX509Svid() just
        // keeps returning the last successfully-pushed snapshot with no
        // proactive invalidation. Logged at WARN so it's visible before
        // the cert actually expires, not just after.
        private void checkExpiryMargin() {
            try {
                Instant notAfter = x509Source.getX509Svid().getChain().get(0).getNotAfter().toInstant();
                Duration remaining = Duration.between(Instant.now(), notAfter);
                if (remaining.compareTo(EXPIRY_WARNING_MARGIN) < 0) {
                    log.warn("SPIFFE SVID expires in {} — Workload API may not be delivering rotations "
                                    + "(a healthy connection rotates well before this point); "
                                    + "connectors will start rejecting/being rejected once it lapses",
                            remaining);
                }
            } catch (Exception e) {
                log.warn("Unable to check SPIFFE SVID expiry margin", e);
            }
        }

        @Override
        public void stop() {
            if (reconcileExecutor != null) {
                reconcileExecutor.shutdownNow();
            }
            if (watchClient != null) {
                try {
                    watchClient.close();
                } catch (Exception e) {
                    log.warn("Error closing SPIFFE SVID watch client", e);
                }
            }
            running = false;
        }

        @Override
        public boolean isRunning() {
            return running;
        }
    }
}
