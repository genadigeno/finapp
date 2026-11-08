// The rules' own test: `semgrep --test` over this directory, offline, on every run of infra/scripts/sast.sh.
package plant;

import java.io.ByteArrayInputStream;
import java.io.ObjectInputStream;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Random;
import javax.crypto.Cipher;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSession;
import javax.net.ssl.X509TrustManager;
import javax.xml.parsers.DocumentBuilderFactory;

class JavaSecurity {

    private static final String WEAK = "MD5";
    private static final String STRONG = "SHA-256";
    private static final String GCM = "AES/GCM/NoPadding";
    private static final String BARE = "AES";

    Object deserialize(byte[] bytes) throws Exception {
        // ruleid: finapp-java-deserialization
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            return in.readObject();
        }
    }

    void command(String program) throws Exception {
        // ruleid: finapp-java-command-execution
        Runtime.getRuntime().exec(program);
        // ruleid: finapp-java-command-execution
        new ProcessBuilder(program).start();
    }

    void digests() throws Exception {
        // ruleid: finapp-java-weak-digest
        MessageDigest.getInstance("MD5");
        // ruleid: finapp-java-weak-digest
        MessageDigest.getInstance("SHA-1");
        // ruleid: finapp-java-weak-digest
        MessageDigest.getInstance(WEAK);
        // ok: finapp-java-weak-digest
        MessageDigest.getInstance(STRONG);
        // ok: finapp-java-weak-digest
        MessageDigest.getInstance("SHA-256");
    }

    void ciphers() throws Exception {
        // ruleid: finapp-java-weak-cipher
        Cipher.getInstance("DES/CBC/PKCS5Padding");
        // ruleid: finapp-java-weak-cipher
        Cipher.getInstance("AES/ECB/PKCS5Padding");
        // ruleid: finapp-java-weak-cipher
        Cipher.getInstance(BARE);
        // ok: finapp-java-weak-cipher
        Cipher.getInstance(GCM);
        // ok: finapp-java-weak-cipher
        Cipher.getInstance("AES/GCM/NoPadding");
    }

    void randoms() {
        // ruleid: finapp-java-insecure-random
        new Random().nextInt();
        // ruleid: finapp-java-insecure-random
        double value = Math.random();
        // ok: finapp-java-insecure-random
        new SecureRandom().nextInt();
    }

    static class TrustAll implements X509TrustManager {
        // ruleid: finapp-java-trust-everything
        public void checkServerTrusted(X509Certificate[] chain, String authType) { }

        public void checkClientTrusted(X509Certificate[] chain, String authType) { }

        public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }

    static class TrustChecked implements X509TrustManager {
        // ok: finapp-java-trust-everything
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
            if (chain.length == 0) {
                throw new IllegalArgumentException("empty");
            }
        }

        public void checkClientTrusted(X509Certificate[] chain, String authType) { }

        public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }

    static class AnyHost implements HostnameVerifier {
        // ruleid: finapp-java-trust-everything
        public boolean verify(String hostname, SSLSession session) { return true; }
    }

    void hostnames() {
        // ruleid: finapp-java-trust-everything
        HttpsURLConnection.setDefaultHostnameVerifier(new AnyHost());
        // ruleid: finapp-java-trust-everything
        System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true");
    }

    void xml() throws Exception {
        // ruleid: finapp-java-xml-external-entities
        DocumentBuilderFactory open = DocumentBuilderFactory.newInstance();
        open.newDocumentBuilder();
        // ok: finapp-java-xml-external-entities
        DocumentBuilderFactory closed = DocumentBuilderFactory.newInstance();
        closed.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    }
}
