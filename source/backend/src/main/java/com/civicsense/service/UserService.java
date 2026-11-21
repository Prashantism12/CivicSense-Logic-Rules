package com.civicsense.service;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.logging.Logger;

import com.civicsense.dao.UserDAO;
import com.civicsense.model.User;
import com.civicsense.util.OtpUtil;
import com.civicsense.util.PasswordUtil;

public class UserService {

    private static final Logger logger = Logger.getLogger(UserService.class.getName());
    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final int LOCK_MINUTES = 30;

    private final UserDAO userDAO;
    private final NotificationService notificationService;

    public UserService(UserDAO userDAO, NotificationService notificationService) {
        this.userDAO = userDAO;
        this.notificationService = notificationService;
    }

    /** Creates an UNVERIFIED citizen and e-mails a 6-digit OTP. The account is usable only after verifyOtp(). */
    public User registerPending(String firstName, String lastName, String email,
                                String mobile, String rawPassword, String ward) {
        String mail = email.toLowerCase().trim();
        String mob = (mobile == null || mobile.trim().isEmpty()) ? null : mobile.trim();
        java.util.Optional<User> existing = userDAO.findByEmail(mail);
        if (existing.isPresent() && Boolean.TRUE.equals(existing.get().getEmailVerified()))
            throw new IllegalArgumentException("Email already registered. Please sign in instead.");
        if (mob != null && userDAO.existsByMobile(mob)
                && !(existing.isPresent() && mob.equals(existing.get().getMobile())))
            throw new IllegalArgumentException("Mobile number already registered.");

        User u = existing.orElseGet(User::new);   // re-registering an unverified address just refreshes it
        u.setFirstName(firstName.trim());
        u.setLastName(lastName.trim());
        u.setEmail(mail);
        u.setMobile(mob);
        u.setPassword(PasswordUtil.hash(rawPassword));
        u.setWard(ward);
        u.setRole(User.Role.CITIZEN);
        u.setEmailVerified(false);
        String otp = OtpUtil.generate();
        u.setOtp(PasswordUtil.hash(otp));
        u.setOtpExpiry(LocalDateTime.now().plusMinutes(10));
        if (existing.isPresent()) userDAO.update(u); else userDAO.save(u);
        notificationService.sendEmailOtp(mail, otp, "Verify your CivicSense account");
        logger.info("Registration OTP sent to " + mail);
        return u;
    }

    /** Sends a fresh OTP to an existing (unverified) account. */
    public void resendOtp(String email) {
        User u = userDAO.findByEmail(email.toLowerCase().trim())
                .orElseThrow(() -> new IllegalArgumentException("No account found for this e-mail."));
        String otp = OtpUtil.generate();
        u.setOtp(PasswordUtil.hash(otp));
        u.setOtpExpiry(LocalDateTime.now().plusMinutes(10));
        userDAO.update(u);
        notificationService.sendEmailOtp(u.getEmail(), otp, "Your CivicSense verification code");
    }

    /** Roles configured in mail.properties (login.otp.roles) must pass an e-mail OTP at sign-in. */
    public boolean requiresLoginOtp(User u) {
        return com.civicsense.util.MailConfig.loginOtpRoles().contains(u.getRole().name());
    }

    /** Register citizen and immediately verify (no email OTP required in dev mode) */
    public User registerWithoutOtp(String firstName, String lastName, String email,
                                    String mobile, String rawPassword, String ward, User.Role role) {
        if (userDAO.existsByEmail(email))
            throw new IllegalArgumentException("Email already registered. Please sign in instead.");
        if (mobile != null && !mobile.isEmpty() && userDAO.existsByMobile(mobile))
            throw new IllegalArgumentException("Mobile number already registered.");

        User u = new User();
        u.setFirstName(firstName.trim());
        u.setLastName(lastName.trim());
        u.setEmail(email.toLowerCase().trim());
        u.setMobile(mobile != null && mobile.trim().isEmpty() ? null : mobile);
        u.setPassword(PasswordUtil.hash(rawPassword));
        u.setWard(ward);
        u.setRole(role != null ? role : User.Role.CITIZEN);
        u.setEmailVerified(true);  // Auto-verify in dev mode

        userDAO.save(u);
        logger.info("User registered (no-OTP): " + email);
        return u;
    }

    public User login(String email, String rawPassword, String role) {
        User u = userDAO.findByEmail(email.toLowerCase())
                .orElseThrow(() -> new RuntimeException("No account found with this email."));

        if (u.isAccountLocked())
            throw new RuntimeException("Account locked until " + u.getLockedUntil() + ". Contact support.");

        if (!PasswordUtil.matches(rawPassword, u.getPassword())) {
            int attempts = u.getFailedLoginAttempts() + 1;
            u.setFailedLoginAttempts(attempts);
            if (attempts >= MAX_FAILED_ATTEMPTS) {
                u.setLockedUntil(LocalDateTime.now().plusMinutes(LOCK_MINUTES));
                u.setFailedLoginAttempts(0);
                userDAO.update(u);
                throw new RuntimeException("Too many failed attempts. Account locked for " + LOCK_MINUTES + " minutes.");
            }
            userDAO.update(u);
            throw new RuntimeException("Invalid password. " + (MAX_FAILED_ATTEMPTS - attempts) + " attempts remaining.");
        }

        // Role mismatch check
        if (role != null && !role.isEmpty()) {
            User.Role expectedRole = User.Role.valueOf(role.toUpperCase());
            if (u.getRole() != expectedRole)
                throw new RuntimeException("Access denied. This account does not have " + role + " access.");
        }

        if (u.getRole() == User.Role.CITIZEN && !Boolean.TRUE.equals(u.getEmailVerified())) {
            resendOtp(u.getEmail());
            throw new UnverifiedEmailException(u.getEmail());
        }

        u.setFailedLoginAttempts(0);
        u.setLastLogin(LocalDateTime.now());
        userDAO.update(u);
        return u;
    }

    public void sendOtpForLogin(User user) {
    try {
        String otp = OtpUtil.generate();

        user.setOtp(PasswordUtil.hash(otp));
        user.setOtpExpiry(LocalDateTime.now().plusMinutes(5));

        userDAO.update(user);

        notificationService.sendEmailOtp(
                user.getEmail(),
                otp,
                "Your CivicSense login OTP"
        );

        logger.info("Login OTP sent successfully to " + user.getEmail());

    } catch (Exception e) {
        logger.severe("Failed to send login OTP: " + e.getMessage());
        e.printStackTrace();
        throw new RuntimeException("Unable to send OTP. Please try again.", e);
        }
    }

    public boolean verifyOtp(User user, String inputOtp) {

        if (user.getOtp() == null || user.getOtpExpiry() == null) {
            logger.warning("OTP not found for user: " + user.getEmail());
            return false;
        }

        if (LocalDateTime.now().isAfter(user.getOtpExpiry())) {
            logger.warning("OTP expired for user: " + user.getEmail());
            return false;
        }

        boolean ok = PasswordUtil.matches(inputOtp, user.getOtp());

        if (ok) {
            user.setOtp(null);
            user.setOtpExpiry(null);
            user.setEmailVerified(true);
            userDAO.update(user);

            logger.info("OTP verified successfully for " + user.getEmail());
        }

        return ok;
    }

    public void sendPasswordReset(String email) {
        User u = userDAO.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("No account found with this email."));
        String token = OtpUtil.generateToken();
        u.setResetToken(token);
        u.setResetTokenExpiry(LocalDateTime.now().plusHours(1));
        userDAO.update(u);
        notificationService.sendPasswordReset(u.getEmail(), token);
    }

    public void resetPassword(String token, String newPassword) {
        User u = userDAO.findByResetToken(token)
                .orElseThrow(() -> new RuntimeException("Invalid or expired reset link."));
        if (LocalDateTime.now().isAfter(u.getResetTokenExpiry()))
            throw new RuntimeException("Reset link has expired. Please request a new one.");
        u.setPassword(PasswordUtil.hash(newPassword));
        u.setResetToken(null);
        u.setResetTokenExpiry(null);
        userDAO.update(u);
    }

    public User updateProfile(Long userId, String firstName, String lastName,
                               String mobile, String ward, String language) {
        User u = userDAO.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));
        if (firstName != null) u.setFirstName(firstName);
        if (lastName  != null) u.setLastName(lastName);
        if (mobile    != null) u.setMobile(mobile);
        if (ward      != null && !ward.isEmpty()) u.setWard(ward);
        if (language  != null) u.setPreferredLanguage(language);
        userDAO.update(u);
        logger.info("User logged in successfully: " + u.getEmail());
        return u;
    }


    public java.util.List<User> getAllCitizens(int page, int size) {
        return userDAO.findByRole(User.Role.CITIZEN);
    }

    public long countCitizens() {
        return userDAO.countByRole(User.Role.CITIZEN);
    }

    public Optional<User> findByEmail(String email) { return userDAO.findByEmail(email); }
    public Optional<User> findById(Long id) { return userDAO.findById(id); }
}
