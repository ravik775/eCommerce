package org.bgm.common.spiffe;

import io.spiffe.svid.x509svid.X509Svid;
import io.spiffe.workloadapi.X509Source;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Collection;
import java.util.List;

/**
 * Writes the current SVID (leaf cert + full chain + private key) and
 * trust bundle to PEM files Tomcat can load via its standard
 * {@code certificateFile}/{@code certificateKeyFile}/{@code
 * certificateChainFile}/{@code caCertificateFile} configuration —
 * chosen over wiring a pre-built {@link javax.net.ssl.SSLContext}
 * directly into {@code SSLHostConfigCertificate} after finding that path
 * has known, documented bugs in some Spring Boot/Tomcat combinations
 * (custom SSLContext silently ignored, falls back to a default keystore
 * — see spring-projects/spring-boot#47326). File-based config is the
 * same mechanism SPIRE's own {@code spiffe-helper} sidecar uses, just
 * done in-process instead of via a second container.
 * <p>
 * Files are rewritten every time this is called; callers are
 * responsible for re-invoking on a schedule matching SVID rotation and
 * triggering Tomcat's SSL reload afterward (see
 * {@link SpiffeInboundMtlsAutoConfiguration}).
 */
final class SpiffePemFileWriter {

    private SpiffePemFileWriter() {
    }

    record PemPaths(Path certificateFile, Path certificateKeyFile, Path certificateChainFile, Path caCertificateFile) {
    }

    static PemPaths writeCurrentSvid(X509Source x509Source, Path directory) {
        try {
            Files.createDirectories(directory);
            X509Svid svid = x509Source.getX509Svid();

            List<X509Certificate> chain = svid.getChain();
            Path certFile = directory.resolve("svid.pem");
            Path chainFile = directory.resolve("chain.pem");
            Path keyFile = directory.resolve("svid-key.pem");
            Path caFile = directory.resolve("bundle.pem");

            writePem(certFile, chain.get(0));
            // certificateChainFile must hold only the certs ABOVE the leaf
            // (the CA chain) — certificateFile already supplies the leaf
            // separately. Passing the full chain (leaf included) here
            // made Tomcat send the leaf twice, since this trust domain's
            // SPIRE server signs SVIDs directly with no intermediate CA
            // (chain.size() == 1): a receiving java-spiffe-provider
            // client then tried to validate the duplicate leaf as if it
            // were the leaf's own issuer, and correctly rejected it —
            // "CA key usage check failed: keyCertSign bit is not set" —
            // found live on the first real gateway->catalog-service call
            // once both sides had mTLS enabled, not caught by any
            // single-service TLS-rejection test. An empty chain.pem is
            // valid and expected in this single-tier trust domain.
            writeChainPem(chainFile, chain.subList(1, chain.size()));
            writeKeyPem(keyFile, svid.getPrivateKey().getEncoded());
            writeBundlePem(caFile, x509Source.getBundleForTrustDomain(svid.getSpiffeId().getTrustDomain()).getX509Authorities());

            return new PemPaths(certFile, keyFile, chainFile, caFile);
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to write SPIFFE SVID PEM files", e);
        } catch (io.spiffe.exception.BundleNotFoundException e) {
            throw new IllegalStateException("No trust bundle available for this SVID's trust domain", e);
        }
    }

    private static void writePem(Path path, X509Certificate certificate) throws IOException {
        try (OutputStream out = Files.newOutputStream(path, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING, java.nio.file.StandardOpenOption.WRITE)) {
            writePemBlock(out, "CERTIFICATE", encode(certificate));
        }
    }

    private static void writeChainPem(Path path, List<X509Certificate> chain) throws IOException {
        try (OutputStream out = Files.newOutputStream(path, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING, java.nio.file.StandardOpenOption.WRITE)) {
            for (X509Certificate cert : chain) {
                writePemBlock(out, "CERTIFICATE", encode(cert));
            }
        }
    }

    private static void writeBundlePem(Path path, Collection<X509Certificate> authorities) throws IOException {
        try (OutputStream out = Files.newOutputStream(path, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING, java.nio.file.StandardOpenOption.WRITE)) {
            for (X509Certificate cert : authorities) {
                writePemBlock(out, "CERTIFICATE", encode(cert));
            }
        }
    }

    private static void writeKeyPem(Path path, byte[] pkcs8EncodedKey) throws IOException {
        try (OutputStream out = Files.newOutputStream(path, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING, java.nio.file.StandardOpenOption.WRITE)) {
            writePemBlock(out, "PRIVATE KEY", Base64.getEncoder().encodeToString(pkcs8EncodedKey));
        }
        // Private key file: owner read/write only. Best-effort — silently
        // no-ops on filesystems that don't support POSIX permissions
        // (acceptable here since the containing directory is already a
        // pod-private emptyDir, not shared with other pods).
        try {
            Files.setPosixFilePermissions(path, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // non-POSIX filesystem
        }
    }

    private static String encode(X509Certificate certificate) {
        try {
            return Base64.getEncoder().encodeToString(certificate.getEncoded());
        } catch (CertificateEncodingException e) {
            throw new IllegalStateException("Unable to encode X509 certificate", e);
        }
    }

    private static void writePemBlock(OutputStream out, String type, String base64) throws IOException {
        out.write(("-----BEGIN " + type + "-----\n").getBytes());
        for (int i = 0; i < base64.length(); i += 64) {
            out.write(base64.substring(i, Math.min(i + 64, base64.length())).getBytes());
            out.write('\n');
        }
        out.write(("-----END " + type + "-----\n").getBytes());
    }
}
