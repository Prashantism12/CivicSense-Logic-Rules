package com.civicsense.util;
import org.mindrot.jbcrypt.BCrypt;

public class PasswordUtil {
    private static final int ROUNDS = 12;
    public static String hash(String raw) { return BCrypt.hashpw(raw, BCrypt.gensalt(ROUNDS)); }
    public static boolean matches(String raw, String hashed) {
        if (raw == null || hashed == null) return false;
        return BCrypt.checkpw(raw, hashed);
    }
    public static boolean isStrong(String p) {
        if (p == null || p.length() < 8) return false;
        return p.chars().anyMatch(Character::isUpperCase)
            && p.chars().anyMatch(Character::isLowerCase)
            && p.chars().anyMatch(Character::isDigit);
    }
}
