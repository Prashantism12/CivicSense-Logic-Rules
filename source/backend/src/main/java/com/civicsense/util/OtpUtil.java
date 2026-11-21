package com.civicsense.util;
import java.security.SecureRandom;
import java.util.UUID;

public class OtpUtil {
    private static final SecureRandom rng = new SecureRandom();
    public static String generate() { return String.format("%06d", rng.nextInt(1_000_000)); }
    public static String generateToken() { return UUID.randomUUID().toString().replace("-", ""); }
}
