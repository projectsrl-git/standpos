package it.projectsrl.standpos.device;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/** Device tokens (256 random bits, base64url) and one-time enrolment codes (8 unambiguous characters). */
public final class DeviceTokens {

    public static final String COOKIE = "SP_DEVICE";

    /** No 0/O, 1/I/L: easy to read aloud and to type on a touch keyboard. */
    static final String CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    static final int CODE_LENGTH = 8;

    private static final SecureRandom RANDOM = new SecureRandom();

    private DeviceTokens() {}

    public static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static byte[] hash(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String newEnrolmentCode() {
        StringBuilder code = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            code.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
        }
        return code.toString();
    }

    /** Uppercases and strips spaces and dashes, so "abcd-efgh" is accepted for "ABCDEFGH". */
    public static String normaliseCode(String input) {
        return input == null ? "" : input.replaceAll("[\\s-]", "").toUpperCase(java.util.Locale.ROOT);
    }
}
