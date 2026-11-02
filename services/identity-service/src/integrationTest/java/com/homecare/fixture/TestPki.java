package com.homecare.fixture;

import java.nio.file.*;
import java.security.KeyStore;
import java.security.SecureRandom;
import javax.net.ssl.*;
import java.util.*;

/** Ephemeral key material under build output, not source control or logs. */
public final class TestPki {
    public final Path directory;
    public final String password;
    public TestPki(Path directory) throws Exception {
        this.directory = directory; Files.createDirectories(directory);
        byte[] secret = new byte[32]; new SecureRandom().nextBytes(secret);
        password = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        keytool("-genkeypair", "-alias", "ca", "-dname", "CN=HomeCare-Fixture-CA", "-keyalg", "RSA", "-keysize", "2048",
                "-ext", "bc=ca:true", "-validity", "2", "-keystore", file("ca.p12"));
        keytool("-exportcert", "-alias", "ca", "-rfc", "-keystore", file("ca.p12"), "-file", file("ca.pem"));
        keytool("-importcert", "-noprompt", "-alias", "ca", "-file", file("ca.pem"), "-keystore", file("trust.p12"));
        keytool("-genkeypair", "-alias", "outsider", "-dname", "CN=gateway", "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "2", "-keystore", file("outsider.p12"));
        for (String name : List.of("identity", "gateway", "rogue", "token")) {
            keytool("-genkeypair", "-alias", name, "-dname", "CN=" + name, "-keyalg", "RSA", "-keysize", "2048",
                    "-validity", "2", "-keystore", file(name + ".p12"));
            keytool("-certreq", "-alias", name, "-keystore", file(name + ".p12"), "-file", file(name + ".csr"));
            keytool("-gencert", "-alias", "ca", "-keystore", file("ca.p12"), "-infile", file(name + ".csr"),
                    "-outfile", file(name + ".pem"), "-rfc", "-validity", "2", "-ext", "SAN=DNS:localhost,IP:127.0.0.1",
                    "-ext", "EKU=serverAuth,clientAuth");
            keytool("-importcert", "-noprompt", "-alias", "ca", "-file", file("ca.pem"), "-keystore", file(name + ".p12"));
            keytool("-importcert", "-alias", name, "-file", file(name + ".pem"), "-keystore", file(name + ".p12"));
        }
    }
    public String file(String name) { return directory.resolve(name).toAbsolutePath().toString(); }
    public KeyStore store(String name) throws Exception {
        var store = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(directory.resolve(name + ".p12"))) { store.load(input, password.toCharArray()); }
        return store;
    }
    public SSLContext ssl(String name) throws Exception {
        var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); trust.init(store("trust"));
        KeyManager[] keys = null;
        if (name != null) {
            var manager = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            manager.init(store(name), password.toCharArray()); keys = manager.getKeyManagers();
        }
        var context = SSLContext.getInstance("TLS"); context.init(keys, trust.getTrustManagers(), new SecureRandom()); return context;
    }
    private void keytool(String... args) throws Exception {
        var command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "keytool").toString());
        command.addAll(List.of(args)); command.addAll(List.of("-storetype", "PKCS12", "-storepass:env", "TEST_KEYSTORE_PASSWORD"));
        var builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().put("TEST_KEYSTORE_PASSWORD", password);
        var process = builder.start(); process.getInputStream().readAllBytes();
        if (process.waitFor() != 0) throw new IllegalStateException("Fixture keytool failed (credentials/output suppressed)");
    }
}
