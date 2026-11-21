package com.civicsense.service;

import java.util.logging.Logger;

import com.civicsense.dao.NotificationDAO;
import com.civicsense.model.Complaint;
import com.civicsense.model.Notification;
import com.civicsense.model.User;
import com.civicsense.util.HdmcData;
import com.civicsense.util.MailConfig;

/** In-app notifications are saved to the DB (all portals read the same rows); e-mail goes through MailConfig. */
public class NotificationService {

    private static final Logger logger = Logger.getLogger(NotificationService.class.getName());
    private NotificationDAO notificationDAO;

    public void setNotificationDAO(NotificationDAO dao) { this.notificationDAO = dao; }

    public void notifyComplaintSubmitted(Complaint c) {
        User citizen = c.getCitizen();
        String title = "Complaint Submitted: " + c.getComplaintId();
        String msg = "Your complaint \"" + c.getTitle() + "\" has been received. We'll keep you updated.";
        sendAppNotif(citizen, title, msg, Notification.NotifType.COMPLAINT_SUBMITTED, c.getComplaintId());
        sendEmailQuietly(citizen.getEmail(), title, buildEmailBody(c, msg));
    }

    public void notifyComplaintAssigned(Complaint c) {
        User citizen = c.getCitizen();
        String empName = c.getAssignedEmployee() != null ? c.getAssignedEmployee().getName() : "a field officer";
        String msg = "Your complaint " + c.getComplaintId() + " has been assigned to " + empName + " for resolution.";
        sendAppNotif(citizen, "Officer Assigned", msg, Notification.NotifType.COMPLAINT_ASSIGNED, c.getComplaintId());
        if (c.getAssignedEmployee() != null && c.getAssignedEmployee().getUser() != null) {
            sendAppNotif(c.getAssignedEmployee().getUser(), "New task assigned: " + c.getComplaintId(),
                    c.getTitle(), Notification.NotifType.COMPLAINT_ASSIGNED, c.getComplaintId());
        }
    }

    public void notifyComplaintResolved(Complaint c) {
        String msg = "Your complaint " + c.getComplaintId() + " has been resolved. Please check the photo and rate the work.";
        sendAppNotif(c.getCitizen(), "Complaint Resolved", msg, Notification.NotifType.COMPLAINT_RESOLVED, c.getComplaintId());
        sendEmailQuietly(c.getCitizen().getEmail(), "Your complaint has been resolved", buildEmailBody(c, msg));
    }

    public void notifyEscalation(Complaint c, String escalateTo) {
        String msg = "Complaint " + c.getComplaintId() + " has been escalated to " + escalateTo + ".";
        sendAppNotif(c.getCitizen(), "Complaint Escalated", msg, Notification.NotifType.ESCALATION, c.getComplaintId());
    }

    public void sendStatusUpdate(Complaint c, String message) {
        sendAppNotif(c.getCitizen(), "Update on " + c.getComplaintId(), message,
                Notification.NotifType.WORK_STARTED, c.getComplaintId());
    }

    /** Tells an employee something about their task (e.g. citizen re-opened it). */
    public void notifyUser(User user, String title, String message, String complaintId) {
        if (user != null) sendAppNotif(user, title, message, Notification.NotifType.SYSTEM, complaintId);
    }

    /** OTP mails MUST reach the user, so failures are thrown to the caller. */
    public void sendEmailOtp(String email, String otp, String subject) {
        String body = "<div style='font-family:Arial,sans-serif;max-width:520px;'>"
                + "<h2 style='color:#1E4D7B;'>" + HdmcData.CORPORATION_NAME + "</h2>"
                + "<p>Your CivicSense verification code is:</p>"
                + "<p style='font-size:28px;font-weight:700;letter-spacing:6px;'>" + otp + "</p>"
                + "<p>Valid for 10 minutes. Do not share this code with anyone.</p></div>";
        sendEmail(email, subject, body);
    }

    public void sendPasswordReset(String email, String token) {
        String link = MailConfig.baseUrl() + "/reset-password?token=" + token;
        String body = "Click the link below to reset your CivicSense password:<br><br><a href='" + link + "'>" + link
                + "</a><br><br>This link expires in 1 hour.";
        sendEmail(email, "Reset your CivicSense password", body);
    }

    // ── internals ──

    private void sendAppNotif(User user, String title, String message, Notification.NotifType type, String complaintId) {
        if (user == null) return;
        try {
            Notification n = new Notification();
            n.setUser(user);
            n.setTitle(title);
            n.setMessage(message);
            n.setType(type);
            n.setChannel(Notification.Channel.APP);
            n.setComplaintId(complaintId);
            if (notificationDAO != null) notificationDAO.save(n);
        } catch (Exception e) {
            logger.warning("Could not save notification: " + e.getMessage());
        }
    }

    private void sendEmailQuietly(String to, String subject, String body) {
        try { sendEmail(to, subject, body); }
        catch (Exception e) { logger.warning("E-mail not sent to " + to + ": " + e.getMessage()); }
    }

    private void sendEmail(String to, String subject, String htmlBody) {
        if (to == null || to.isEmpty()) return;
        if (!MailConfig.isConfigured()) {
            throw new RuntimeException("E-mail is not configured. Set mail.user and mail.password in mail.properties.");
        }
        final String user = MailConfig.user();
        final String pass = MailConfig.password();
        java.util.Properties props = new java.util.Properties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.starttls.required", "true");
        props.put("mail.smtp.host", MailConfig.host());
        props.put("mail.smtp.port", MailConfig.port());
        props.put("mail.smtp.ssl.protocols", "TLSv1.2 TLSv1.3");
        props.put("mail.smtp.ssl.trust", MailConfig.host());
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout", "15000");
        props.put("mail.smtp.writetimeout", "15000");

        javax.mail.Session session = javax.mail.Session.getInstance(props, new javax.mail.Authenticator() {
            @Override
            protected javax.mail.PasswordAuthentication getPasswordAuthentication() {
                return new javax.mail.PasswordAuthentication(user, pass);
            }
        });
        try {
            javax.mail.internet.MimeMessage msg = new javax.mail.internet.MimeMessage(session);
            msg.setFrom(new javax.mail.internet.InternetAddress(user, MailConfig.fromName(), "UTF-8"));
            msg.setRecipients(javax.mail.Message.RecipientType.TO, javax.mail.internet.InternetAddress.parse(to));
            msg.setSubject(subject, "UTF-8");
            msg.setContent(htmlBody, "text/html; charset=utf-8");
            javax.mail.Transport.send(msg);
            logger.info("[EMAIL SENT] To=" + to + " Subject=" + subject);
        } catch (Exception e) {
            logger.severe("[EMAIL FAILED] to=" + to + " : " + e);
            throw new RuntimeException("Unable to send e-mail: " + e.getMessage(), e);
        }
    }

    private String buildEmailBody(Complaint c, String intro) {
        String td = "<td style='padding:8px;'>";
        String th = "<td style='padding:8px;background:#f9fafb;font-weight:600;'>";
        return "<div style='font-family:Arial,sans-serif;max-width:600px;'>"
            + "<p>" + intro + "</p>"
            + "<table style='width:100%;border-collapse:collapse;margin-top:16px;'>"
            + "<tr>" + th + "Complaint ID</td>" + td + c.getComplaintId() + "</td></tr>"
            + "<tr>" + th + "Issue</td>" + td + c.getTitle() + "</td></tr>"
            + "<tr>" + th + "Category</td>" + td + c.getCategory() + "</td></tr>"
            + "<tr>" + th + "Status</td>" + td + c.getStatus() + "</td></tr>"
            + "<tr>" + th + "Location</td>" + td + c.getLocation() + "</td></tr>"
            + "</table>"
            + "<p style='margin-top:20px;'><a href='" + MailConfig.baseUrl() + "/citizen/complaint/" + c.getComplaintId()
            + "' style='background:#1E6FBF;color:white;padding:10px 20px;border-radius:8px;text-decoration:none;'>Track Your Complaint</a></p>"
            + "<p style='font-size:12px;color:#9CA3AF;margin-top:24px;'>CivicSense | " + HdmcData.CORPORATION_NAME
            + "<br>Do not reply to this email.</p></div>";
    }
}
