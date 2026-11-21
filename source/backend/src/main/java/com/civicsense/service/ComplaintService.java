package com.civicsense.service;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

import com.civicsense.dao.ComplaintDAO;
import com.civicsense.dao.DepartmentDAO;
import com.civicsense.dao.EmployeeDAO;
import com.civicsense.dao.UserDAO;
import com.civicsense.model.Complaint;
import com.civicsense.model.ComplaintImage;
import com.civicsense.model.Department;
import com.civicsense.model.Employee;
import com.civicsense.model.Feedback;
import com.civicsense.model.User;
import com.civicsense.util.GeoUtil;
import com.civicsense.util.IdGenerator;
import com.civicsense.util.LevenshteinUtil;

/**
 * Complaint service — handles complaint submission, duplicate detection,
 * department/employee assignment, SLA, escalation and complaint queries.
 */
public class ComplaintService {

    private static final Logger logger =
            Logger.getLogger(ComplaintService.class.getName());

    private final ComplaintDAO complaintDAO;
    private final DepartmentDAO departmentDAO;
    private final EmployeeDAO employeeDAO;
    private final UserDAO userDAO;
    private final NotificationService notificationService;

    // SLA in hours per category
    private static final Map<Complaint.Category, Integer> SLA_HOURS = Map.of(
        Complaint.Category.ROAD_DAMAGE,     72,
        Complaint.Category.GARBAGE,         24,
        Complaint.Category.STREET_LIGHT,    48,
        Complaint.Category.WATER_LEAKAGE,   24,
        Complaint.Category.DRAINAGE,        48,
        Complaint.Category.PUBLIC_PROPERTY, 120,
        Complaint.Category.SANITATION,      24,
        Complaint.Category.OTHERS,          72
    );

    // Department mapping by category
    private static final Map<Complaint.Category, String> DEPT_MAP = Map.of(
        Complaint.Category.ROAD_DAMAGE,     "ROADS",
        Complaint.Category.GARBAGE,         "SWM",
        Complaint.Category.STREET_LIGHT,    "HESCOM",
        Complaint.Category.WATER_LEAKAGE,   "WATER",
        Complaint.Category.DRAINAGE,        "DRAINAGE",
        Complaint.Category.PUBLIC_PROPERTY, "PWD",
        Complaint.Category.SANITATION,      "HEALTH",
        Complaint.Category.OTHERS,          "GENERAL"
    );

    public ComplaintService(
            ComplaintDAO complaintDAO,
            DepartmentDAO departmentDAO,
            EmployeeDAO employeeDAO,
            UserDAO userDAO,
            NotificationService notificationService) {

        this.complaintDAO = complaintDAO;
        this.departmentDAO = departmentDAO;
        this.employeeDAO = employeeDAO;
        this.userDAO = userDAO;
        this.notificationService = notificationService;
    }

    /**
     * Submit a new complaint — full pipeline:
     * 1. Duplicate check
     * 2. Image handling
     * 3. Generate complaint ID
     * 4. Auto-assign department
     * 5. Set SLA deadline
     * 6. Save complaint
     * 7. Auto-assign employee
     * 8. Award citizen points
     * 9. Send notifications
     */
    public Complaint submitComplaint(
            Complaint complaint,
            List<InputStream> imageStreams,
            List<String> imagePaths,
            User citizen) throws Exception {

        // ---------------------------------------------------------
        // 1. Check for duplicate
        // ---------------------------------------------------------
        DuplicateResult dup = checkDuplicate(
                complaint.getTitle(),
                complaint.getLatitude(),
                complaint.getLongitude()
        );

        if (dup.isDuplicate && dup.similarity > 0.85) {
            complaint.setIsDuplicate(true);
            complaint.setParentComplaintId(dup.complaintId);

            // Still allow submission but mark as duplicate.
        }

        // ---------------------------------------------------------
        // 2. Handle uploaded images
        // ---------------------------------------------------------
        //
        // IMPORTANT:
        // The uploaded image is no longer rejected based on
        // sharpness/brightness/image-quality score here.
        //
        // The ComplaintServlet is responsible for checking that
        // the uploaded file is actually an image and is within
        // the allowed file-size limit.
        //
        // This avoids normal mobile photos being rejected by
        // ImageIO/Laplacian quality checks.
        // ---------------------------------------------------------
        if (imageStreams != null && !imageStreams.isEmpty()) {

            for (int i = 0; i < imageStreams.size(); i++) {

                /*
                 * Image validation is intentionally not used as a
                 * submission blocker here.
                 *
                 * A successfully received image gets an
                 * informational score of 1.0.
                 */
                double score = 1.0;

                ComplaintImage ci = new ComplaintImage();

                ci.setComplaint(complaint);
                ci.setAiScore(score);
                ci.setIsValid(true);

                if (imagePaths != null && i < imagePaths.size()) {
                    ci.setImageUrl(imagePaths.get(i));
                }

                complaint.getImages().add(ci);
            }
        }

        // ---------------------------------------------------------
        // 3. Generate complaint ID
        // ---------------------------------------------------------
        String newId = IdGenerator.nextComplaintId(
                complaintDAO.getLastSequenceNumber()
        );

        com.civicsense.util.HdmcData.Ward hw = com.civicsense.util.HdmcData.find(complaint.getWard());
        if (hw != null) {
            complaint.setWard(hw.getLabel());
            complaint.setZone(hw.getZone());
            if (complaint.getLatitude() == null || complaint.getLongitude() == null) {
                complaint.setLatitude(hw.getLat());
                complaint.setLongitude(hw.getLng());
            }
        }
        complaint.setComplaintId(newId);
        complaint.setCitizen(citizen);
        complaint.setStatus(Complaint.Status.SUBMITTED);

        // ---------------------------------------------------------
        // 4. Auto-assign department
        // ---------------------------------------------------------
        String deptCode = DEPT_MAP.getOrDefault(
                complaint.getCategory(),
                "GENERAL"
        );

        Department dept = departmentDAO
                .findByCode(deptCode)
                .orElse(null);

        if (dept != null) {
            complaint.setDepartment(dept);
            complaint.setStatus(Complaint.Status.PENDING);
        }

        // ---------------------------------------------------------
        // 5. Set SLA deadline
        // ---------------------------------------------------------
        int slaHours = SLA_HOURS.getOrDefault(
                complaint.getCategory(),
                72
        );

        if (complaint.getPriority() == Complaint.Priority.HIGH) {
            slaHours = (int) (slaHours * 0.5);
        }

        if (complaint.getPriority() == Complaint.Priority.EMERGENCY) {
            slaHours = 12;
        }

        complaint.setSlaDeadline(
                LocalDateTime.now().plusHours(slaHours)
        );

        // ---------------------------------------------------------
        // 6. Save complaint
        // ---------------------------------------------------------
        complaintDAO.save(complaint);

        // ---------------------------------------------------------
        // 7. Auto-assign employee
        // ---------------------------------------------------------
        // NOTE: no auto-assignment. The admin reviews the complaint and
        // chooses the department officer manually (Admin > Complaints > Assign).

        // ---------------------------------------------------------
        // 8. Award citizen points
        // ---------------------------------------------------------
        citizen.addPoints(10);
        userDAO.update(citizen);

        // ---------------------------------------------------------
        // 9. Send notifications
        // ---------------------------------------------------------
        notificationService.notifyComplaintSubmitted(complaint);

        logger.info(
                "Complaint " + newId +
                " submitted by citizen " +
                citizen.getEmail()
        );

        return complaint;
    }

    /**
     * Duplicate detection:
     * Levenshtein text similarity + Haversine geo distance.
     */
    public DuplicateResult checkDuplicate(
            String title,
            Double lat,
            Double lng) {

        if (title == null || title.trim().isEmpty()) {
            return DuplicateResult.noDuplicate();
        }

        List<Complaint> recent =
                complaintDAO.findRecentOpenComplaints(30);

        for (Complaint c : recent) {

            // Geo proximity check (within 500m)
            if (lat != null
                    && lng != null
                    && c.getLatitude() != null
                    && c.getLongitude() != null) {

                double dist = GeoUtil.haversineDistance(
                        lat,
                        lng,
                        c.getLatitude(),
                        c.getLongitude()
                );

                if (dist > 500) {
                    continue;
                }
            }

            // Title similarity
            double sim = LevenshteinUtil.similarity(
                    title.toLowerCase(),
                    c.getTitle().toLowerCase()
            );

            if (sim >= 0.75) {
                return new DuplicateResult(
                        true,
                        c.getComplaintId(),
                        c.getTitle(),
                        sim,
                        c.getStatus().name()
                );
            }
        }

        return DuplicateResult.noDuplicate();
    }

    /**
     * Auto-assign employee based on lowest active task count
     * in the department.
     */
    public void autoAssignEmployee(Complaint complaint) {

        if (complaint.getDepartment() == null) {
            return;
        }

        Optional<Employee> emp =
                employeeDAO.findLeastLoadedInDept(
                        complaint.getDepartment().getId()
                );

        if (emp.isPresent()) {

            complaint.setAssignedEmployee(emp.get());
            complaint.setStatus(Complaint.Status.ASSIGNED);
            complaint.setAssignedAt(LocalDateTime.now());

            complaintDAO.update(complaint);

            emp.get().setActiveTaskCount(
                    emp.get().getActiveTaskCount() + 1
            );

            employeeDAO.update(emp.get());

            notificationService.notifyComplaintAssigned(complaint);
        }
    }

    /**
     * Admin verify and assign. The admin MUST choose the employee when assigning;
     * with no employee this only marks the complaint VERIFIED.
     */
    public void verifyAndAssign(String complaintId, Long deptId, Long employeeId,
                                Complaint.Priority priority, String notes, User admin) {

        Complaint c = complaintDAO.findByComplaintId(complaintId)
                .orElseThrow(() -> new RuntimeException("Complaint not found: " + complaintId));

        if (deptId != null) {
            c.setDepartment(departmentDAO.findById(deptId)
                    .orElseThrow(() -> new RuntimeException("Department not found")));
        }

        if (employeeId != null) {
            Employee emp = employeeDAO.findById(employeeId)
                    .orElseThrow(() -> new RuntimeException("Employee not found"));
            if (emp.getEmployeeStatus() != Employee.EmployeeStatus.ACTIVE)
                throw new RuntimeException("That employee is not active.");
            if (c.getDepartment() != null && emp.getDepartment() != null
                    && !c.getDepartment().getId().equals(emp.getDepartment().getId()))
                throw new RuntimeException("Choose an employee from the " + c.getDepartment().getName() + " department.");

            Employee old = c.getAssignedEmployee();
            boolean same = old != null && old.getId().equals(emp.getId());
            if (!same) {
                if (old != null) {
                    old.setActiveTaskCount(Math.max(0, old.getActiveTaskCount() - 1));
                    employeeDAO.update(old);
                }
                emp.setActiveTaskCount(emp.getActiveTaskCount() + 1);
                employeeDAO.update(emp);
            }
            c.setAssignedEmployee(emp);
            c.setAssignedAt(LocalDateTime.now());
            c.setStatus(Complaint.Status.ASSIGNED);
            c.addProgress(admin != null ? admin.getName() : "Admin", "Assigned to " + emp.getName());
        } else if (c.getAssignedEmployee() == null) {
            c.setStatus(Complaint.Status.VERIFIED);
            c.addProgress(admin != null ? admin.getName() : "Admin", "Complaint verified");
        }

        if (priority != null) c.setPriority(priority);
        if (notes != null && !notes.trim().isEmpty()) c.setAdminNotes(notes.trim());

        complaintDAO.update(c);
        if (c.getAssignedEmployee() != null) notificationService.notifyComplaintAssigned(c);
        logger.info("Admin processed complaint " + complaintId);
    }

    /**
     * Employee: start work on complaint.
     */
    public void startWork(
            String complaintId,
            Long employeeId) {

        Complaint c = getAndValidateEmployeeAccess(
                complaintId,
                employeeId
        );

        c.setStatus(Complaint.Status.IN_PROGRESS);
        c.addProgress(c.getAssignedEmployee().getName(), "Started work");

        complaintDAO.update(c);

        notificationService.sendStatusUpdate(
                c,
                "Work has started on your complaint."
        );
    }

    /**
     * Employee: submit resolution.
     */
    public void submitResolution(
            String complaintId,
            Long employeeId,
            String resolutionNotes,
            String afterImageUrl) {

        Complaint c = getAndValidateEmployeeAccess(
                complaintId,
                employeeId
        );

        c.setResolutionNotes(resolutionNotes);
        if (afterImageUrl != null) {
            c.setResolutionImageUrl(afterImageUrl);
            ComplaintImage ev = new ComplaintImage();
            ev.setComplaint(c);
            ev.setImageUrl(afterImageUrl);
            ev.setImageType(ComplaintImage.ImageType.AFTER);
            ev.setIsValid(true);
            c.getImages().add(ev);
        }
        c.addProgress(c.getAssignedEmployee().getName(), "Marked as resolved. " + (resolutionNotes == null ? "" : resolutionNotes));

        c.setStatus(
                Complaint.Status.RESOLVED
        );

        c.setResolvedAt(
                LocalDateTime.now()
        );

        complaintDAO.update(c);

        // Update employee stats
        Employee emp = c.getAssignedEmployee();

        if (emp != null) {

            emp.setActiveTaskCount(
                    Math.max(
                            0,
                            emp.getActiveTaskCount() - 1
                    )
            );

            emp.setTotalResolved(
                    emp.getTotalResolved() + 1
            );

            employeeDAO.update(emp);
        }

        notificationService.notifyComplaintResolved(c);
    }

    /**
     * Citizen rates the finished work. Satisfied -> CLOSED. Not satisfied -> the SAME
     * complaint is re-opened (REOPENED) and goes back to the admin to assign again.
     */
    public void citizenVerify(String complaintId, Long citizenId, boolean satisfied, int rating, String comment) {

        Complaint c = complaintDAO.findByComplaintId(complaintId)
                .orElseThrow(() -> new RuntimeException("Complaint not found"));
        if (!c.getCitizen().getId().equals(citizenId)) throw new SecurityException("Unauthorized");
        if (c.getStatus() != Complaint.Status.RESOLVED)
            throw new IllegalStateException("Feedback can be given only after the work is marked resolved.");
        if (rating < 1 || rating > 5) throw new IllegalArgumentException("Please choose a rating from 1 to 5.");

        // 1. Save / update feedback (one row per complaint)
        Feedback fb = c.getFeedback();
        boolean isNew = fb == null;
        if (isNew) { fb = new Feedback(); fb.setComplaint(c); fb.setCitizen(c.getCitizen()); }
        fb.setRating(rating);
        fb.setComments(comment == null ? null : comment.trim());
        fb.setIsResolvedSatisfactorily(satisfied);
        if (isNew) { complaintDAO.saveFeedback(fb); c.setFeedback(fb); }

        Employee emp = c.getAssignedEmployee();
        String citizenName = c.getCitizen().getName();

        c.setCitizenVerified(satisfied);
        c.setCitizenVerifiedAt(LocalDateTime.now());

        if (satisfied) {
            c.setStatus(Complaint.Status.CLOSED);
            c.setClosedAt(LocalDateTime.now());
            c.addProgress(citizenName, "Confirmed the work. Rating " + rating + "/5");
            c.getCitizen().addPoints(5);
            userDAO.update(c.getCitizen());
        } else {
            c.setStatus(Complaint.Status.REOPENED);
            c.setResolvedAt(null);
            c.addProgress(citizenName, "Not satisfied (rating " + rating + "/5) - complaint raised again. "
                    + (comment == null ? "" : comment.trim()));
        }
        complaintDAO.update(c);          // saved while still linked to the employee, so the rating counts for them

        // Employee performance rating = average of all citizen ratings for their complaints
        if (emp != null) {
            try {
                Double avg = complaintDAO.avgRatingForEmployee(emp.getId());
                if (avg != null) { emp.setPerformanceRating(Math.round(avg * 10) / 10.0); }
                employeeDAO.update(emp);
            } catch (Exception e) { logger.warning("Could not update rating: " + e.getMessage()); }
        }

        if (!satisfied) {
            c.setAssignedEmployee(null);      // admin chooses who handles it next
            c.setAssignedAt(null);
            complaintDAO.update(c);
            if (emp != null) {
                notificationService.notifyUser(emp.getUser(), "Complaint re-opened: " + c.getComplaintId(),
                        "The citizen was not satisfied with the work and raised it again.", c.getComplaintId());
            }
        }
    }

    /** Employee adds a progress note visible in all portals. */
    public void addProgressNote(String complaintId, Long employeeId, String note) {
        Complaint c = getAndValidateEmployeeAccess(complaintId, employeeId);
        if (note == null || note.trim().isEmpty()) throw new IllegalArgumentException("Please write an update note.");
        c.addProgress(c.getAssignedEmployee().getName(), note.trim());
        complaintDAO.update(c);
        notificationService.sendStatusUpdate(c, "Update from the officer: " + note.trim());
    }

    /** Employee asks for admin help; complaint shows as ESCALATED to the admin. */
    public void requestHelp(String complaintId, Long employeeId, String reason) {
        Complaint c = getAndValidateEmployeeAccess(complaintId, employeeId);
        c.setIsEscalated(true);
        c.setEscalationLevel(Math.max(1, c.getEscalationLevel() == null ? 0 : c.getEscalationLevel()));
        c.setEscalatedAt(LocalDateTime.now());
        c.setStatus(Complaint.Status.ESCALATED);
        c.addProgress(c.getAssignedEmployee().getName(), "Requested admin help. " + (reason == null ? "" : reason));
        complaintDAO.update(c);
    }

    /**
     * Escalation logic: called by scheduler.
     *
     * 3 days  → Department Head
     * 7 days  → City Admin
     * 15 days → Admin
     */
    public void runEscalationCheck() {

        LocalDateTime now =
                LocalDateTime.now();

        List<Complaint> open =
                complaintDAO.findOpenUnresolvedComplaints();

        for (Complaint c : open) {

            if (c.getAssignedAt() == null) {
                continue;
            }

            long daysSinceAssigned =
                    java.time.temporal.ChronoUnit.DAYS.between(
                            c.getAssignedAt(),
                            now
                    );

            int escLevel =
                    c.getEscalationLevel() == null
                            ? 0
                            : c.getEscalationLevel();

            if (daysSinceAssigned >= 15
                    && escLevel < 3) {

                escalate(
                        c,
                        3,
                        "Admin"
                );

            } else if (daysSinceAssigned >= 7
                    && escLevel < 2) {

                escalate(
                        c,
                        2,
                        "City Admin"
                );

            } else if (daysSinceAssigned >= 3
                    && escLevel < 1) {

                escalate(
                        c,
                        1,
                        "Department Head"
                );
            }
        }
    }

    private void escalate(
            Complaint c,
            int level,
            String escalateTo) {

        c.setIsEscalated(true);

        c.setEscalationLevel(level);

        c.setEscalatedAt(
                LocalDateTime.now()
        );

        c.setStatus(
                Complaint.Status.ESCALATED
        );

        complaintDAO.update(c);

        notificationService.notifyEscalation(
                c,
                escalateTo
        );

        logger.warning(
                "Complaint "
                + c.getComplaintId()
                + " escalated to "
                + escalateTo
        );
    }

    /**
     * Support (vote for) an existing complaint.
     */
    public void supportComplaint(
            String complaintId,
            Long citizenId) {

        Complaint c = complaintDAO
                .findByComplaintId(complaintId)
                .orElseThrow(() ->
                        new RuntimeException(
                                "Complaint not found"
                        )
                );

        c.setSupportCount(
                c.getSupportCount() + 1
        );

        complaintDAO.update(c);

        // Award small points to supporter
        userDAO.findById(citizenId).ifPresent(
                u -> {
                    u.addPoints(2);
                    userDAO.update(u);
                }
        );
    }

    /**
     * Reject complaint.
     */
    public void rejectComplaint(
            String complaintId,
            String reason,
            User admin) {

        Complaint c = complaintDAO
                .findByComplaintId(complaintId)
                .orElseThrow(() ->
                        new RuntimeException(
                                "Complaint not found: "
                                + complaintId
                        )
                );

        c.setStatus(
                Complaint.Status.REJECTED
        );

        if (reason != null && !reason.isEmpty()) {
            c.setAdminNotes(reason);
        }
        c.addProgress(admin != null ? admin.getName() : "Admin", "Complaint rejected. " + (reason == null ? "" : reason));

        complaintDAO.update(c);

        notificationService.sendStatusUpdate(
                c,
                "Your complaint has been reviewed and "
                + "could not be actioned at this time. "
                + "Reason: "
                + (reason != null ? reason : "N/A")
        );

        logger.info(
                "Complaint "
                + complaintId
                + " rejected by admin"
        );
    }

    // ---------------------------------------------------------
    // Queries
    // ---------------------------------------------------------

    public List<Complaint> getComplaintsByCitizen(
            Long citizenId) {

        return complaintDAO.findByCitizenId(
                citizenId
        );
    }

    public List<Complaint> getComplaintsByEmployee(
            Long employeeId) {

        return complaintDAO.findByEmployeeId(
                employeeId
        );
    }

    public List<Complaint> getAllComplaintsByEmployee(Long employeeId) {
        return complaintDAO.findAllByEmployeeId(employeeId);
    }

    public List<Complaint> getComplaintsByDept(
            Long deptId,
            Complaint.Status status) {

        return complaintDAO.findByDeptAndStatus(
                deptId,
                status
        );
    }

    public Optional<Complaint> getByComplaintId(
            String id) {

        return complaintDAO.findByComplaintId(id);
    }

    private Complaint getAndValidateEmployeeAccess(
            String complaintId,
            Long employeeId) {

        Complaint c = complaintDAO
                .findByComplaintId(complaintId)
                .orElseThrow(() ->
                        new RuntimeException(
                                "Complaint not found"
                        )
                );

        if (c.getAssignedEmployee() == null
                || !c.getAssignedEmployee()
                        .getId()
                        .equals(employeeId)) {

            throw new SecurityException(
                    "Unauthorized: this complaint "
                    + "is not assigned to you."
            );
        }

        return c;
    }

    // ---------------------------------------------------------
    // Inner classes
    // ---------------------------------------------------------

    public static class DuplicateResult {

        public final boolean isDuplicate;
        public final String complaintId;
        public final String existingTitle;
        public final double similarity;
        public final String status;

        public DuplicateResult(
                boolean isDuplicate,
                String complaintId,
                String existingTitle,
                double similarity,
                String status) {

            this.isDuplicate = isDuplicate;
            this.complaintId = complaintId;
            this.existingTitle = existingTitle;
            this.similarity = similarity;
            this.status = status;
        }

        public static DuplicateResult noDuplicate() {

            return new DuplicateResult(
                    false,
                    null,
                    null,
                    0.0,
                    null
            );
        }
    }

    // ---------------------------------------------------------
    // Methods required by AdminServlet
    // ---------------------------------------------------------

    public long countAll() {
        return complaintDAO.countAll();
    }

    public long countByStatus(
            Complaint.Status status) {

        return complaintDAO.countByStatus(
                status
        );
    }

    public List<Complaint> findByStatus(
            Complaint.Status status) {

        return complaintDAO.findByStatus(
                status
        );
    }

    public List<Complaint> findAll(
            int page,
            int size) {

        return complaintDAO.findAll(
                page,
                size
        );
    }

    public List<Complaint> searchComplaints(
            String keyword,
            Complaint.Status status,
            Complaint.Category category,
            int page,
            int size) {

        try {

            return complaintDAO.searchComplaints(
                    keyword,
                    status,
                    category,
                    page,
                    size
            );

        } catch (Exception e) {

            logger.warning(
                    "searchComplaints error: "
                    + e.getMessage()
            );

            return new java.util.ArrayList<>();
        }
    }

    public List<Object[]> countByCategory() {
        return complaintDAO.countByCategory();
    }

    public List<Object[]> countByStatusGrouped() {
        return complaintDAO.countByStatusGrouped();
    }

    public double avgResolutionHours() {
        return complaintDAO.avgResolutionHours();
    }

    // ---------------------------------------------------------
    // Update complaint status
    // ---------------------------------------------------------

    public void updateStatus(
            String complaintId,
            String statusStr,
            String notes,
            User admin) {

        Complaint c = complaintDAO
                .findByComplaintId(complaintId)
                .orElseThrow(() ->
                        new RuntimeException(
                                "Complaint not found: "
                                + complaintId
                        )
                );

        try {

            c.setStatus(
                    Complaint.Status.valueOf(
                            statusStr
                    )
            );

        } catch (IllegalArgumentException e) {

            throw new RuntimeException(
                    "Invalid status: "
                    + statusStr
            );
        }

        if (notes != null
                && !notes.trim().isEmpty()) {

            c.setAdminNotes(
                    notes.trim()
            );
        }
        c.addProgress(admin != null ? admin.getName() : "Admin", "Status changed to " + statusStr);

        complaintDAO.update(c);

        notificationService.sendStatusUpdate(
                c,
                "Your complaint status has been "
                + "updated to: "
                + statusStr
        );

        logger.info(
                "Status updated: "
                + complaintId
                + " -> "
                + statusStr
                + " by "
                + (admin != null
                    ? admin.getEmail()
                    : "system")
        );
    }

    // ---------------------------------------------------------
    // Delete complaint
    // ---------------------------------------------------------

    public void deleteComplaint(
            String complaintId,
            User admin) {

        if (complaintId == null
                || complaintId.trim().isEmpty()) {

            throw new IllegalArgumentException(
                    "Complaint ID is required"
            );
        }

        complaintDAO.deleteByComplaintId(
                complaintId
        );

        logger.info(
                "Complaint "
                + complaintId
                + " deleted by "
                + (admin != null
                    ? admin.getEmail()
                    : "system")
        );
    }
}