package com.civicsense.config;

import com.civicsense.dao.*;
import com.civicsense.model.*;
import com.civicsense.service.*;
import com.civicsense.util.HdmcData;
import com.civicsense.util.PasswordUtil;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.Persistence;
import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;
import java.time.LocalDateTime;
import java.util.logging.Logger;

@WebListener
public class AppContextListener implements ServletContextListener {

    private static final Logger logger = Logger.getLogger(AppContextListener.class.getName());
    private EntityManagerFactory emf;

    @Override
    public void contextInitialized(ServletContextEvent sce) {
        logger.info("=== CivicSense starting up ===");
        ServletContext ctx = sce.getServletContext();

        try {
            // 1. JPA EntityManagerFactory
            emf = Persistence.createEntityManagerFactory("CivicSensePU");
            EntityManager em = emf.createEntityManager();
            ctx.setAttribute("emf", emf);
            ctx.setAttribute("em", em);

            // 2. DAOs
            UserDAO       userDAO  = new UserDAO(em);
            ComplaintDAO  cDAO     = new ComplaintDAO(em);
            DepartmentDAO deptDAO  = new DepartmentDAO(em);
            EmployeeDAO   empDAO   = new EmployeeDAO(em);

            ctx.setAttribute("userDAO",  userDAO);
            ctx.setAttribute("cDAO",     cDAO);
            ctx.setAttribute("deptDAO",  deptDAO);
            ctx.setAttribute("empDAO",   empDAO);

            // 3. Services
            NotificationDAO notifDAO = new NotificationDAO(em);
            ctx.setAttribute("notifDAO", notifDAO);
            ctx.setAttribute("hdmcWards", com.civicsense.util.HdmcData.getWards());
            ctx.setAttribute("corpName", com.civicsense.util.HdmcData.CORPORATION_NAME);
            NotificationService notifSvc = new NotificationService();
            notifSvc.setNotificationDAO(notifDAO);
            UserService         userSvc  = new UserService(userDAO, notifSvc);
            ComplaintService    cSvc     = new ComplaintService(cDAO, deptDAO, empDAO, userDAO, notifSvc);

            ctx.setAttribute("notifService",     notifSvc);
            ctx.setAttribute("userService",      userSvc);
            ctx.setAttribute("complaintService", cSvc);

            // 4. Seed demo data if empty
            seedDemoData(em, userDAO, cDAO, deptDAO, empDAO);
            migrateToHdmc(em, userDAO, deptDAO, empDAO);

            // 5. Schedule escalation checker
            java.util.concurrent.Executors.newScheduledThreadPool(1)
                .scheduleAtFixedRate(cSvc::runEscalationCheck, 1, 6,
                    java.util.concurrent.TimeUnit.HOURS);

            logger.info("=== CivicSense ready ===");

        } catch (Exception e) {
            logger.severe("Failed to initialize CivicSense: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }

    @Override
    public void contextDestroyed(ServletContextEvent sce) {
        if (emf != null && emf.isOpen()) emf.close();
        logger.info("=== CivicSense shutdown ===");
    }

    // ── Seed demo accounts ──
    private void seedDemoData(EntityManager em, UserDAO userDAO, ComplaintDAO cDAO, DepartmentDAO deptDAO, EmployeeDAO empDAO) {
        // Only seed if no users exist
        Long userCount = em.createQuery("SELECT COUNT(u) FROM User u", Long.class).getSingleResult();
        if (userCount > 0) { logger.info("Seed skipped — data already exists."); return; }

        logger.info("Seeding Hubballi-Dharwad demo data...");
        String pass = PasswordUtil.hash("Demo@1234");
        java.util.List<HdmcData.Ward> w = HdmcData.getWards();

        // ── Demo citizens (verified) ──
        createUser(userDAO, "Rajesh", "Kumar",  "citizen@demo.com",  null, pass, ward(w, 32).getLabel(), User.Role.CITIZEN);
        createUser(userDAO, "Priya",  "Hiremath","citizen2@demo.com", null, pass, ward(w, 1).getLabel(),  User.Role.CITIZEN);
        createUser(userDAO, "Anand",  "Patil",  "citizen3@demo.com", null, pass, ward(w, 46).getLabel(), User.Role.CITIZEN);

        // ── Departments ──
        Department roads  = createDept(deptDAO, "Roads Department",            "ROADS",    "🚧", "#EF4444", 72,  "Suresh Nair",    "roads@hdmc.gov.in");
        Department swm    = createDept(deptDAO, "Solid Waste Management",      "SWM",      "🗑️", "#10B981", 24,  "Radha Krishnan", "swm@hdmc.gov.in");
        Department hescom = createDept(deptDAO, "Street Lighting (HESCOM)",    "HESCOM",   "💡", "#F59E0B", 48,  "Kiran Rao",      "lighting@hescom.in");
        Department water  = createDept(deptDAO, "Water Supply (HDMC)",         "WATER",    "💧", "#3B82F6", 24,  "Meena Devi",     "water@hdmc.gov.in");
        Department drain  = createDept(deptDAO, "Drainage Department",         "DRAINAGE", "🚰", "#8B5CF6", 48,  "Arun Kumar",     "drain@hdmc.gov.in");
        Department health = createDept(deptDAO, "Public Health & Sanitation",  "HEALTH",   "🏥", "#EC4899", 24,  "Dr. Sunita R.",  "health@hdmc.gov.in");
        Department parks  = createDept(deptDAO, "Parks & Gardens",             "PARKS",    "🌿", "#059669", 96,  "Gopal Nair",     "parks@hdmc.gov.in");
        Department pwd    = createDept(deptDAO, "Public Works Dept",           "PWD",      "🏗️", "#6B7280", 120, "Venkatesha B.",  "pwd@hdmc.gov.in");
        Department general= createDept(deptDAO, "General Administration",      "GENERAL",  "🏛️", "#0EA5E9", 72,  "Shivanand K.",   "general@hdmc.gov.in");

        // ── Admin ──
        createUser(userDAO, "Admin", "User", "admin@demo.com", null, pass, null, User.Role.ADMIN);

        // ── Exactly EMPLOYEES_PER_DEPT field employees per department ──
        Department[] depts = { roads, swm, hescom, water, drain, health, parks, pwd, general };
        String[][] names = {
            {"Suresh Reddy","Ravi Kumar"}, {"Ramesh Bhat","Lakshmi Naik"}, {"Kiran Teli","Deepak Nayak"},
            {"Meera Joshi","Basavaraj Patil"}, {"Arun Hegde","Shobha Kulkarni"}, {"Sunil Desai","Anita Rao"},
            {"Gopal Mane","Rekha Shetty"}, {"Venkatesh Gouda","Mahesh Angadi"}, {"Shivu Hiremath","Pooja Kamat"}
        };
        for (int d = 0; d < depts.length; d++) {
            for (int i = 0; i < EMPLOYEES_PER_DEPT; i++) {
                String[] n = names[d][i].split(" ", 2);
                String email = "emp." + depts[d].getCode().toLowerCase() + (i + 1) + "@demo.com";
                User u = createUser(userDAO, n[0], n[1], email, null, pass, null, User.Role.EMPLOYEE);
                createEmployee(empDAO, u, depts[d], designationFor(depts[d].getCode()));
            }
        }

        // ── Sample complaints (all in the 20 HDMC wards). New complaints from citizens start unassigned. ──
        User citizen1 = userDAO.findByEmail("citizen@demo.com").orElse(null);
        User citizen2 = userDAO.findByEmail("citizen2@demo.com").orElse(null);
        Employee roadsEmp = empDAO.findByDept(roads.getId()).stream().findFirst().orElse(null);
        Employee swmEmp   = empDAO.findByDept(swm.getId()).stream().findFirst().orElse(null);

        if (citizen1 != null) {
            createComplaint(cDAO, "CS-2026-1301", citizen1, Complaint.Category.ROAD_DAMAGE,
                "Large pothole causing accidents near Vidyanagar main road",
                "Deep pothole approximately 2 feet wide. Causing bike damage and accidents.",
                Complaint.Priority.HIGH, Complaint.Status.IN_PROGRESS, roads, roadsEmp,
                "Vidyanagar Main Road, near bus stop", ward(w, 32));
            createComplaint(cDAO, "CS-2026-1290", citizen1, Complaint.Category.GARBAGE,
                "Garbage not collected for 4 days near apartment complex",
                "Overflowing bins outside the apartments causing health hazard and bad smell.",
                Complaint.Priority.MEDIUM, Complaint.Status.ASSIGNED, swm, swmEmp,
                "Near Shirur Park apartments", ward(w, 32));
            createComplaint(cDAO, "CS-2026-1280", citizen1, Complaint.Category.STREET_LIGHT,
                "Street light not working near school gate for 5 days",
                "Streetlight outside the school is dark, unsafe for children.",
                Complaint.Priority.HIGH, Complaint.Status.PENDING, hescom, null,
                "Keshwapur school gate", ward(w, 37));
        }
        if (citizen2 != null) {
            createComplaint(cDAO, "CS-2026-1320", citizen2, Complaint.Category.DRAINAGE,
                "Sewage overflow near school creating health hazard",
                "Sewage overflowing from a blocked drain near the school. Children at health risk.",
                Complaint.Priority.EMERGENCY, Complaint.Status.PENDING, drain, null,
                "Near Govt School, Vidyagiri", ward(w, 1));
            createComplaint(cDAO, "CS-2026-1325", citizen2, Complaint.Category.WATER_LEAKAGE,
                "Water pipe burst causing road flooding",
                "A main water pipe has burst near the bus stop causing flooding.",
                Complaint.Priority.HIGH, Complaint.Status.PENDING, water, null,
                "Saidapur main road", ward(w, 21));
        }
        if (roadsEmp != null) { roadsEmp.setActiveTaskCount(1); empDAO.update(roadsEmp); }
        if (swmEmp != null)   { swmEmp.setActiveTaskCount(1);   empDAO.update(swmEmp); }
        if (citizen1 != null) { citizen1.setCivicPoints(240); userDAO.update(citizen1); }
        if (citizen2 != null) { citizen2.setCivicPoints(180); userDAO.update(citizen2); }

        logger.info("Demo data seeded. Login: citizen@demo.com / admin@demo.com / emp.roads1@demo.com  (password Demo@1234)");
    }

    private static final int EMPLOYEES_PER_DEPT = 2;

    private static HdmcData.Ward ward(java.util.List<HdmcData.Ward> all, int number) {
        for (HdmcData.Ward x : all) if (x.getNumber() == number) return x;
        return all.get(0);
    }

    private static String designationFor(String code) {
        switch (code) {
            case "SWM": return "Sanitation Worker";
            case "HESCOM": return "Electrical Technician";
            case "WATER": return "Water Works Technician";
            case "DRAINAGE": return "Drainage Inspector";
            case "HEALTH": return "Health Inspector";
            case "PARKS": return "Garden Supervisor";
            default: return "Field Officer";
        }
    }

    /**
     * One-time, idempotent conversion of an existing (Bengaluru) database to Hubballi-Dharwad:
     * wards/zones/coordinates, department names, and exactly EMPLOYEES_PER_DEPT active employees per department.
     */
    private void migrateToHdmc(EntityManager em, UserDAO userDAO, DepartmentDAO deptDAO, EmployeeDAO empDAO) {
        try {
            em.getTransaction().begin();
            java.util.List<HdmcData.Ward> wards = HdmcData.getWards();

            // 1. citizens' wards
            for (User u : em.createQuery("SELECT u FROM User u WHERE u.role = :r", User.class)
                    .setParameter("r", User.Role.CITIZEN).getResultList()) {
                if (!HdmcData.isValidWard(u.getWard())) {
                    HdmcData.Ward hw = HdmcData.find(u.getWard());
                    u.setWard((hw != null ? hw : wards.get((int) (u.getId() % wards.size()))).getLabel());
                }
            }
            // 2. complaints' ward / zone / coordinates
            for (Complaint c : em.createQuery("SELECT c FROM Complaint c", Complaint.class).getResultList()) {
                if (!HdmcData.isValidWard(c.getWard())) {
                    HdmcData.Ward hw = HdmcData.find(c.getWard());
                    if (hw == null) hw = wards.get((int) (c.getId() % wards.size()));
                    c.setWard(hw.getLabel());
                    c.setZone(hw.getZone());
                    double j = ((c.getId() % 7) - 3) * 0.0008;
                    c.setLatitude(hw.getLat() + j);
                    c.setLongitude(hw.getLng() - j);
                }
            }
            // 3. departments
            for (Department d : em.createQuery("SELECT d FROM Department d", Department.class).getResultList()) {
                if ("BESCOM".equals(d.getCode())) { d.setCode("HESCOM"); d.setName("Street Lighting (HESCOM)"); }
                if ("BWSSB".equals(d.getCode()))  { d.setCode("WATER");  d.setName("Water Supply (HDMC)"); }
                if (d.getHeadEmail() != null) d.setHeadEmail(d.getHeadEmail().replace("@bbmp.gov.in", "@hdmc.gov.in").replace("@bwssb.gov.in", "@hdmc.gov.in"));
                if (d.getName() != null) d.setName(d.getName().replace("BBMP", "HDMC"));
            }
            em.getTransaction().commit();

            // 4. employees: exactly N active per department
            em.getTransaction().begin();
            String pass = PasswordUtil.hash("Demo@1234");
            if (!deptDAO.findByCode("GENERAL").isPresent()) {
                createDept(deptDAO, "General Administration", "GENERAL", "🏛️", "#0EA5E9", 72, "Shivanand K.", "general@hdmc.gov.in");
            }
            for (Department d : em.createQuery("SELECT d FROM Department d WHERE d.isActive = TRUE", Department.class).getResultList()) {
                java.util.List<Employee> active = em.createQuery(
                    "SELECT e FROM Employee e WHERE e.department.id = :d AND e.employeeStatus = com.civicsense.model.Employee$EmployeeStatus.ACTIVE ORDER BY e.id",
                    Employee.class).setParameter("d", d.getId()).getResultList();
                for (int i = EMPLOYEES_PER_DEPT; i < active.size(); i++) {      // surplus -> inactive, work moved to a kept officer
                    Employee extra = active.get(i);
                    extra.setEmployeeStatus(Employee.EmployeeStatus.INACTIVE);
                    Employee keep = active.get(i % EMPLOYEES_PER_DEPT);
                    for (Complaint c : em.createQuery("SELECT c FROM Complaint c WHERE c.assignedEmployee.id = :e", Complaint.class)
                            .setParameter("e", extra.getId()).getResultList()) c.setAssignedEmployee(keep);
                    extra.setActiveTaskCount(0);
                }
                for (int i = active.size(); i < EMPLOYEES_PER_DEPT; i++) {      // missing -> create
                    String email = "emp." + d.getCode().toLowerCase() + (i + 1) + "@demo.com";
                    if (userDAO.existsByEmail(email)) email = "emp." + d.getCode().toLowerCase() + "x" + System.nanoTime() % 10000 + "@demo.com";
                    User u = createUser(userDAO, "Officer" + (i + 1), d.getCode(), email, null, pass, null, User.Role.EMPLOYEE);
                    createEmployee(empDAO, u, d, designationFor(d.getCode()));
                }
            }
            em.getTransaction().commit();
            logger.info("Hubballi-Dharwad migration check complete.");
        } catch (Exception e) {
            if (em.getTransaction().isActive()) em.getTransaction().rollback();
            logger.warning("HDMC migration skipped: " + e);
        }
    }

    private User createUser(UserDAO dao, String fn, String ln, String email, String mobile, String hash, String ward, User.Role role) {
        User u = new User();
        u.setFirstName(fn); u.setLastName(ln); u.setEmail(email);
        u.setMobile(mobile); u.setPassword(hash); u.setWard(ward);
        u.setRole(role); u.setEmailVerified(true);
        dao.save(u);
        return u;
    }

    private Department createDept(DepartmentDAO dao, String name, String code, String icon, String color,
                                   int sla, String head, String email) {
        Department d = new Department();
        d.setName(name); d.setCode(code); d.setIcon(icon); d.setColor(color);
        d.setSlaHours(sla); d.setHeadName(head); d.setHeadEmail(email);
        dao.save(d);
        return d;
    }

    private Employee createEmployee(EmployeeDAO dao, User user, Department dept, String designation) {
        Employee e = new Employee();
        e.setUser(user); e.setDepartment(dept); e.setDesignation(designation);
        e.setJoiningDate(LocalDateTime.now().minusMonths((long)(Math.random()*24)));
        dao.save(e);
        return e;
    }
    private Complaint createComplaint(ComplaintDAO dao, String id, User citizen,
            Complaint.Category category, String title, String description,
            Complaint.Priority priority, Complaint.Status status,
            Department dept, Employee emp, String location, HdmcData.Ward ward) {
        Complaint c = new Complaint();
        c.setComplaintId(id);
        c.setCitizen(citizen);
        c.setCategory(category);
        c.setTitle(title);
        c.setDescription(description);
        c.setPriority(priority);
        c.setSeverity(Complaint.Severity.MODERATE);
        c.setStatus(status);
        c.setDepartment(dept);
        c.setAssignedEmployee(emp);
        c.setLocation(location + ", " + ward.getCity());
        c.setWard(ward.getLabel());
        c.setZone(ward.getZone());
        c.setLatitude(ward.getLat());
        c.setLongitude(ward.getLng());
        LocalDateTime now = LocalDateTime.now();
        c.setSlaDeadline(now.plusHours(48));
        if (emp != null) c.setAssignedAt(now.minusHours(6));
        dao.save(c);
        return c;
    }

}
