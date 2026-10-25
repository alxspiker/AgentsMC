package dev.agentsmc;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/** Per-body bearer credentials. Only their SHA-256 digests are persisted. */
public final class AgentCredential {
    private AgentCredential() { }

    public static String mint() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return "amc_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String digest(String token) {
        if (token == null || !token.matches("amc_[A-Za-z0-9_-]{43}"))
            throw new IllegalArgumentException("Invalid agent credential");
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte value : bytes) hex.append(String.format("%02x", value & 255));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Required SHA-256 algorithm is unavailable", e);
        }
    }

    public static boolean matches(String digest, String storedDigest) {
        if (storedDigest == null || !storedDigest.matches("[0-9a-f]{64}"))
            throw new IllegalStateException("Stored agent credential digest is malformed");
        return MessageDigest.isEqual(digest.getBytes(StandardCharsets.US_ASCII), storedDigest.getBytes(StandardCharsets.US_ASCII));
    }
}
