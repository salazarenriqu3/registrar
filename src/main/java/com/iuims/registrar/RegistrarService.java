package com.iuims.registrar;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class RegistrarService {

    @Autowired
    private JdbcTemplate db;

    @PostConstruct
    public void init() {
        ensureUserPassword("admin", "1234");
        ensureUserPassword("prof.turing", "1234");
        
        try {
            db.execute("CREATE TABLE IF NOT EXISTS system_settings (setting_key VARCHAR(50) PRIMARY KEY, setting_value VARCHAR(100))");
            
            if (db.queryForObject("SELECT COUNT(*) FROM system_settings", Integer.class) == 0) {
                db.update("INSERT INTO system_settings VALUES ('PRELIM_START', '2025-01-01'), ('PRELIM_END', '2026-12-31'), ('MIDTERM_START', '2025-01-01'), ('MIDTERM_END', '2026-12-31'), ('FINAL_START', '2025-01-01'), ('FINAL_END', '2026-12-31')");
            }
            
            db.execute("CREATE TABLE IF NOT EXISTS payments (payment_id INT AUTO_INCREMENT PRIMARY KEY, student_id INT, amount DECIMAL(10,2), reference_number VARCHAR(50), receipt_image LONGTEXT, status VARCHAR(20) DEFAULT 'PENDING', payment_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");

            try { db.execute("ALTER TABLE class_schedules ADD COLUMN is_unlocked TINYINT DEFAULT 0"); } catch (Exception ignored) {}
            try { db.execute("ALTER TABLE curriculum_catalog ADD COLUMN has_lab TINYINT DEFAULT 0"); } catch (Exception ignored) {}
            try { db.execute("ALTER TABLE student_grades ADD COLUMN lab_prelim DECIMAL(5,2) DEFAULT 0.00, ADD COLUMN lab_midterm DECIMAL(5,2) DEFAULT 0.00, ADD COLUMN lab_final DECIMAL(5,2) DEFAULT 0.00, ADD COLUMN lab_semestral_grade DECIMAL(5,2) DEFAULT 0.00, ADD COLUMN lab_remarks VARCHAR(20) DEFAULT 'Ongoing'"); } catch (Exception ignored) {}
            
        } catch (Exception e) {
            System.err.println(">> SYSTEM: DB Migration logic executed.");
        }
    }

    private void ensureUserPassword(String username, String rawPassword) {
        try {
            List<Map<String, Object>> users = db.queryForList("SELECT * FROM sys_users WHERE username = ?", username);
            if (!users.isEmpty()) {
                String newHash = org.mindrot.jbcrypt.BCrypt.hashpw(rawPassword, org.mindrot.jbcrypt.BCrypt.gensalt());
                db.update("UPDATE sys_users SET password = ? WHERE username = ?", newHash, username);
            }
        } catch (Exception e) {}
    }

    // ==========================================
    // AUTO-ENROLLMENT & PAYMENTS (NEW LOGIC)
    // ==========================================
    public void submitPayment(int studentId, double amount, String referenceNumber, String base64Image) {
        db.update("INSERT INTO payments (student_id, amount, reference_number, receipt_image) VALUES (?, ?, ?, ?)", 
                  studentId, amount, referenceNumber, base64Image);
    }

    public List<Map<String, Object>> getPendingPayments() {
        String sql = "SELECT p.*, u.real_name, u.username FROM payments p JOIN sys_users u ON p.student_id = u.user_id WHERE p.status = 'PENDING' ORDER BY p.payment_date ASC";
        return db.queryForList(sql);
    }

    public List<Map<String, Object>> getStudentPayments(int studentId) {
        return db.queryForList("SELECT * FROM payments WHERE student_id = ? ORDER BY payment_date DESC", studentId);
    }

    @Transactional
    public void verifyPayment(int paymentId) {
        // 1. Verify the payment
        db.update("UPDATE payments SET status = 'VERIFIED' WHERE payment_id = ?", paymentId);
        
        // 2. Fetch the student ID tied to this payment
        Integer studentId = db.queryForObject("SELECT student_id FROM payments WHERE payment_id = ?", Integer.class, paymentId);
        
        if (studentId != null) {
            // 3. Trigger Auto-Enrollment
            autoEnrollStudent(studentId);
        }
    }

    @Transactional
    public void autoEnrollStudent(int studentId) {
        // Fetch student's year level and name
        Map<String, Object> student = db.queryForMap("SELECT year_level, real_name FROM sys_users WHERE user_id = ?", studentId);
        int yearLevel = (int) student.get("year_level");
        String realName = (String) student.get("real_name");

        // Find all OPEN schedules that match the student's year level
        String sql = "SELECT s.schedule_id FROM class_schedules s JOIN curriculum_catalog c ON s.course_code = c.course_code WHERE c.year_level = ? AND s.status = 'OPEN'";
        List<Integer> matchingSchedules = db.queryForList(sql, Integer.class, yearLevel);

        for (Integer schedId : matchingSchedules) {
            try {
                // Ensure they aren't already enrolled to prevent errors
                int count = db.queryForObject("SELECT COUNT(*) FROM enrollment_details WHERE student_id = ? AND schedule_id = ?", Integer.class, studentId, schedId);
                if (count == 0) {
                    // Enroll the student
                    db.update("INSERT INTO enrollment_details (student_id, schedule_id) VALUES (?, ?)", studentId, schedId);
                    // Create their Grade Row for the Professor instantly
                    db.update("INSERT INTO student_grades (student_id, schedule_id, student_name, status) VALUES (?, ?, ?, 'DRAFT')", studentId, schedId, realName);
                }
            } catch (Exception e) {
                // Ignore duplicates smoothly
            }
        }
    }

    public void rejectPayment(int paymentId) {
        db.update("UPDATE payments SET status = 'REJECTED' WHERE payment_id = ?", paymentId);
    }

    // ==========================================
    // SYSTEM SETTINGS & GRADING WINDOWS
    // ==========================================
    public Map<String, Object> getGradingWindows() {
        List<Map<String, Object>> list = db.queryForList("SELECT * FROM system_settings");
        Map<String, Object> settings = new HashMap<>();
        
        for (Map<String, Object> row : list) {
            settings.put((String) row.get("setting_key"), row.get("setting_value"));
        }
        
        java.time.LocalDate today = java.time.LocalDate.now();
        settings.put("prelim_open", isDateInRange(today, (String)settings.get("PRELIM_START"), (String)settings.get("PRELIM_END")));
        settings.put("midterm_open", isDateInRange(today, (String)settings.get("MIDTERM_START"), (String)settings.get("MIDTERM_END")));
        settings.put("final_open", isDateInRange(today, (String)settings.get("FINAL_START"), (String)settings.get("FINAL_END")));
        
        return settings;
    }

    private boolean isDateInRange(java.time.LocalDate date, String start, String end) {
        try {
            java.time.LocalDate s = java.time.LocalDate.parse(start);
            java.time.LocalDate e = java.time.LocalDate.parse(end);
            return !date.isBefore(s) && !date.isAfter(e);
        } catch (Exception ex) {
            return false;
        }
    }

    public void updateSettings(Map<String, String> params) {
        String[] keys = {"PRELIM_START", "PRELIM_END", "MIDTERM_START", "MIDTERM_END", "FINAL_START", "FINAL_END"};
        for (String k : keys) {
            if (params.containsKey(k)) {
                db.update("UPDATE system_settings SET setting_value = ? WHERE setting_key = ?", params.get(k), k);
            }
        }
    }

    public void toggleClassUnlock(int scheduleId, int unlockStatus) {
        db.update("UPDATE class_schedules SET is_unlocked = ? WHERE schedule_id = ?", unlockStatus, scheduleId);
    }

    // ==========================================
    // SEARCH & ADMISSION
    // ==========================================
    public List<Map<String, Object>> searchStudentsBySurname(String q) {
        String sql = "SELECT username, real_name FROM sys_users WHERE (role = 'Student' OR role = 'student') AND LOWER(real_name) LIKE LOWER(?) LIMIT 10";
        return db.queryForList(sql, "%" + q + "%");
    }

    public Map<String, Object> findStudentByIdOrName(String q) { 
        try { 
            return db.queryForMap("SELECT * FROM sys_users WHERE username = ?", q); 
        } catch (Exception e) { 
            return null; 
        }
    }

    @Transactional
    public String acceptAdmission(String applicantId, String program, String yearLevel, String curriculum, String email, String phone, String address, String bday, String sex) {
        try {
            String fullName = db.queryForObject("SELECT full_name FROM admission_applications WHERE applicant_id = ?", String.class, applicantId);
            String yearPrefix = String.valueOf(java.time.Year.now().getValue());
            Integer count = db.queryForObject("SELECT COUNT(*) FROM sys_users WHERE role LIKE 'Student'", Integer.class);
            String studentNumber = String.format("%s-%04d", yearPrefix, (count + 1));
            String hashedPass = org.mindrot.jbcrypt.BCrypt.hashpw("1234", org.mindrot.jbcrypt.BCrypt.gensalt());
            
            String sql = "INSERT INTO sys_users (username, password, real_name, role, program_code, year_level, is_active, email, contact_number, address, birth_date, sex) VALUES (?, ?, ?, 'Student', ?, ?, 1, ?, ?, ?, ?, ?)";
            db.update(sql, studentNumber, hashedPass, fullName, program, yearLevel, email, phone, address, bday, sex);
            
            db.update("UPDATE admission_applications SET status = 'ACCEPTED' WHERE applicant_id = ?", applicantId);
            return studentNumber;
        } catch (Exception e) { 
            return "ERROR"; 
        }
    }

    public List<Map<String, Object>> getPendingApplications() {
        return db.queryForList("SELECT * FROM admission_applications WHERE status = 'PENDING'");
    }

    // ==========================================
    // CORE ENROLLMENT & BILLING
    // ==========================================
    public Map<String, Object> login(String u, String p) {
        try {
            Map<String, Object> user = db.queryForMap("SELECT * FROM sys_users WHERE username=?", u);
            if (org.mindrot.jbcrypt.BCrypt.checkpw(p, (String) user.get("password"))) {
                return user;
            }
        } catch (Exception e) {}
        return null;
    }

    public List<Map<String, Object>> getStudentLoad(int sid) {
        try { 
            return formatClassList(db.queryForList("CALL sp_GetStudentLoad(?)", sid)); 
        } catch (Exception e) { 
            return new ArrayList<>(); 
        }
    }

    public int getTotalUnits(int sid) {
        try { 
            String sql = "SELECT COALESCE(SUM(c.units),0) FROM enrollment_details e JOIN class_schedules s ON e.schedule_id=s.schedule_id JOIN curriculum_catalog c ON s.course_code=c.course_code WHERE e.student_id=?";
            return db.queryForObject(sql, Integer.class, sid); 
        } catch (Exception e) { 
            return 0; 
        }
    }

    public Map<String, Object> calculateAssessment(int sid) {
        Map<String, Object> m = new HashMap<>();
        double tuition = getTotalUnits(sid) * 1500.0;
        double misc = 5500.0;
        double total = tuition + misc;
        
        double totalPaid = 0.0;
        try {
            Double result = db.queryForObject("SELECT SUM(amount) FROM payments WHERE student_id = ? AND status = 'VERIFIED'", Double.class, sid);
            if (result != null) {
                totalPaid = result;
            }
        } catch (Exception e) {}
        
        double balance = total - totalPaid;

        m.put("tuition_fee", tuition);
        m.put("misc_fee", misc);
        m.put("total_assessment", total);
        m.put("total_paid", totalPaid);
        m.put("balance", balance);
        
        m.put("tuition_fee_fmt", String.format("%,.2f", tuition));
        m.put("misc_fee_fmt", String.format("%,.2f", misc));
        m.put("total_assessment_fmt", String.format("%,.2f", total));
        m.put("total_paid_fmt", String.format("%,.2f", totalPaid));
        m.put("balance_fmt", String.format("%,.2f", Math.max(0, balance)));
        
        return m;
    }
    
    public List<Map<String, Object>> getAnalyzedOfferings(int sid) {
        List<Map<String, Object>> classes = formatClassList(db.queryForList("CALL sp_GetAllClasses()"));
        List<String> enrolled = db.queryForList("SELECT s.course_code FROM enrollment_details e JOIN class_schedules s ON e.schedule_id = s.schedule_id WHERE e.student_id = ?", String.class, sid);
        
        for (Map<String, Object> c : classes) {
            boolean isEnrolled = enrolled.contains((String) c.get("course_code"));
            c.put("is_disabled", isEnrolled);
            c.put("reason_msg", isEnrolled ? "Enrolled" : "");
        }
        return classes;
    }

    public Map<Integer, List<Map<String, Object>>> getStudentAcademicHistory(int sid) {
        String sql = "SELECT s.course_code, s.description, g.semestral_grade, g.remarks, s.curriculum_year FROM student_grades g JOIN class_schedules cs ON g.schedule_id = cs.schedule_id JOIN curriculum_catalog s ON cs.course_code = s.course_code WHERE g.student_id = ? AND g.status = 'SUBMITTED' ORDER BY s.curriculum_year DESC";
        List<Map<String, Object>> raw = db.queryForList(sql, sid);
        Map<Integer, List<Map<String, Object>>> h = new LinkedHashMap<>();
        
        for (Map<String, Object> r : raw) {
            h.computeIfAbsent((int) r.get("curriculum_year"), k -> new ArrayList<>()).add(r);
        }
        return h;
    }

    public String checkScheduleConflict(int studentId, int newScheduleId) {
        Map<String, Object> newClass = db.queryForMap("SELECT day, start_time, end_time, course_code FROM class_schedules WHERE schedule_id = ?", newScheduleId);
        List<Map<String, Object>> currentClasses = db.queryForList("SELECT s.day, s.start_time, s.end_time, s.course_code FROM enrollment_details e JOIN class_schedules s ON e.schedule_id = s.schedule_id WHERE e.student_id = ?", studentId);
        
        String newDay = (String) newClass.get("day");
        int newStart = ((Number) newClass.get("start_time")).intValue();
        int newEnd = ((Number) newClass.get("end_time")).intValue();

        for (Map<String, Object> current : currentClasses) {
            if (newDay.equals(current.get("day"))) {
                int currStart = ((Number) current.get("start_time")).intValue();
                int currEnd = ((Number) current.get("end_time")).intValue();
                
                if (newStart < currEnd && newEnd > currStart) {
                    return "Conflict detected with " + current.get("course_code") + " (" + current.get("day") + " " + formatTime(currStart) + "-" + formatTime(currEnd) + ").";
                }
            }
        }
        return null;
    }

    @Transactional
    public String addSubjectDirectly(int sid, int schedId) {
        try {
            // 1. Check Duplicate Enrollment
            int count = db.queryForObject("SELECT COUNT(*) FROM enrollment_details WHERE student_id = ? AND schedule_id = ?", Integer.class, sid, schedId);
            if (count > 0) {
                return "CONFLICT: Student is already enrolled in this subject.";
            }

            // 2. Check Schedule Overlap
            String conflict = checkScheduleConflict(sid, schedId);
            if (conflict != null) {
                return "CONFLICT: " + conflict;
            }

            // 3. Check Class Capacity (FULL CLASS)
            Map<String, Object> classInfo = db.queryForMap("SELECT max_slots, course_code FROM class_schedules WHERE schedule_id = ?", schedId);
            int maxSlots = (int) classInfo.get("max_slots");
            int enrolledCount = db.queryForObject("SELECT COUNT(*) FROM enrollment_details WHERE schedule_id = ?", Integer.class, schedId);
            if (enrolledCount >= maxSlots) {
                return "CONFLICT: Class " + classInfo.get("course_code") + " is already FULL (" + enrolledCount + "/" + maxSlots + " slots taken).";
            }

            // 4. Check Maximum Load Limit (Max 24 Units)
            int currentUnits = getTotalUnits(sid);
            int newClassUnits = db.queryForObject("SELECT c.units FROM class_schedules s JOIN curriculum_catalog c ON s.course_code = c.course_code WHERE s.schedule_id = ?", Integer.class, schedId);
            if ((currentUnits + newClassUnits) > 24) {
                return "CONFLICT: Adding this subject exceeds the maximum allowed unit load of 24 units. (Current: " + currentUnits + ", Adding: " + newClassUnits + ")";
            }

            // 5. Enroll Student
            db.update("INSERT INTO enrollment_details (student_id, schedule_id) VALUES (?, ?)", sid, schedId);
            
            // 6. Generate blank grade template automatically
            String realName = db.queryForObject("SELECT real_name FROM sys_users WHERE user_id = ?", String.class, sid);
            db.update("INSERT INTO student_grades (student_id, schedule_id, student_name, status) VALUES (?, ?, ?, 'DRAFT')", sid, schedId, realName);

            return "Success";
        } catch (Exception e) { 
            return "Error processing enrollment."; 
        }
    }
    
    @Transactional 
    public String dropSubjectDirectly(int sid, int schedId) {
        db.update("DELETE FROM enrollment_details WHERE student_id=? AND schedule_id=?", sid, schedId);
        return "Dropped";
    }

    // ==========================================
    // GRADING LOGIC
    // ==========================================
    @Transactional 
    public Map<String, Object> saveGradeAsync(int gradeId, String prelimStr, String midStr, String finalStr, String gradeType) {
        double p = parseScore(prelimStr);
        double m = parseScore(midStr);
        double f = parseScore(finalStr);
        
        double pointGrade = 0.0;
        String remarks = "";

        if (p == 0 && m == 0 && f == 0) {
            pointGrade = 0.0;
            remarks = "Ongoing";
        } else if (p == 0 || m == 0 || f == 0) {
            pointGrade = 0.0;
            remarks = "INC";
        } else {
            double average = (p + m + f) / 3.0;
            pointGrade = convertToPointGrade(average);
            remarks = (pointGrade > 3.0) ? "Failed" : "Passed";
        }
        
        int rowsUpdated = 0;
        if ("LAB".equals(gradeType)) {
            rowsUpdated = db.update("UPDATE student_grades SET lab_prelim = ?, lab_midterm = ?, lab_final = ?, lab_semestral_grade = ?, lab_remarks = ? WHERE grade_id = ? AND status = 'DRAFT'", p, m, f, pointGrade, remarks, gradeId);
        } else {
            rowsUpdated = db.update("UPDATE student_grades SET prelim = ?, midterm = ?, final = ?, semestral_grade = ?, remarks = ? WHERE grade_id = ? AND status = 'DRAFT'", p, m, f, pointGrade, remarks, gradeId);
        }

        if (rowsUpdated > 0) {
            String classStatus = db.queryForObject("SELECT s.status FROM class_schedules s JOIN student_grades g ON s.schedule_id = g.schedule_id WHERE g.grade_id = ?", String.class, gradeId);
            if ("SUBMITTED".equals(classStatus)) {
                db.update("UPDATE student_grades SET status = 'SUBMITTED' WHERE grade_id = ?", gradeId);
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("semestral_grade", remarks.equals("INC") ? "INC" : String.format("%.2f", pointGrade));
        result.put("remarks", remarks);
        return result;
    }
    
    @Transactional
    public void saveGrade(int gradeId, String prelimStr, String midStr, String finalStr) {
        saveGradeAsync(gradeId, prelimStr, midStr, finalStr, "LEC");
    }
    
    private double parseScore(String s) {
        if (s == null || s.trim().isEmpty()) {
            return 0.0;
        }
        try { 
            return Double.parseDouble(s); 
        } catch (Exception e) { 
            return 0.0; 
        }
    }
    
    private double convertToPointGrade(double avg) {
        if(avg >= 98) return 1.00;
        if(avg >= 95) return 1.25;
        if(avg >= 92) return 1.50;
        if(avg >= 89) return 1.75;
        if(avg >= 86) return 2.00;
        if(avg >= 83) return 2.25;
        if(avg >= 80) return 2.50;
        if(avg >= 77) return 2.75;
        if(avg >= 75) return 3.00;
        return 5.00;
    }

    public List<Map<String, Object>> getFacultyClasses(int facultyId) {
        return formatClassList(db.queryForList("SELECT s.*, c.description FROM class_schedules s JOIN curriculum_catalog c ON s.course_code = c.course_code WHERE s.faculty_id = ?", facultyId));
    }
    
    public List<Map<String, Object>> getClassGrades(int scheduleId) {
        List<Map<String, Object>> grades = db.queryForList("SELECT * FROM student_grades WHERE schedule_id = ?", scheduleId);
        for(Map<String,Object> g : grades) { 
            if(g.get("prelim") == null) g.put("prelim", 0.0); 
            if(g.get("midterm") == null) g.put("midterm", 0.0); 
            if(g.get("final") == null) g.put("final", 0.0); 
            if(g.get("lab_prelim") == null) g.put("lab_prelim", 0.0); 
            if(g.get("lab_midterm") == null) g.put("lab_midterm", 0.0); 
            if(g.get("lab_final") == null) g.put("lab_final", 0.0); 
            if(g.get("lab_semestral_grade") == null) g.put("lab_semestral_grade", 0.0); 
            if(g.get("lab_remarks") == null) g.put("lab_remarks", "Ongoing"); 
        }
        return grades;
    }

    public Map<String, Object> getClassInfo(int scheduleId) {
        Map<String, Object> map = db.queryForMap("SELECT s.*, c.description, c.has_lab FROM class_schedules s JOIN curriculum_catalog c ON s.course_code = c.course_code WHERE s.schedule_id = ?", scheduleId);
        map.put("pretty_schedule", map.get("day") + " " + formatTime(map.get("start_time")) + "-" + formatTime(map.get("end_time")));
        return map;
    }

    @Transactional
    public void submitClassGrades(int scheduleId) {
        db.update("UPDATE student_grades SET status = 'PENDING_APPROVAL' WHERE schedule_id = ?", scheduleId);
        db.update("UPDATE class_schedules SET status = 'PENDING_APPROVAL' WHERE schedule_id = ?", scheduleId);
    }

    @Transactional
    public void unsubmitClassGrades(int scheduleId) {
        db.update("UPDATE student_grades SET status = 'DRAFT' WHERE schedule_id = ? AND status = 'PENDING_APPROVAL'", scheduleId);
        db.update("UPDATE class_schedules SET status = 'OPEN' WHERE schedule_id = ? AND status = 'PENDING_APPROVAL'", scheduleId);
    }
    
    @Transactional
    public void finalizeClassGrades(int scheduleId) {
        db.update("UPDATE student_grades SET status = 'SUBMITTED' WHERE schedule_id = ?", scheduleId);
        db.update("UPDATE class_schedules SET status = 'SUBMITTED' WHERE schedule_id = ?", scheduleId);
    }

    @Transactional
    public void revertClassToDraft(int scheduleId) {
        db.update("UPDATE class_schedules SET status = 'OPEN' WHERE schedule_id = ?", scheduleId);
        db.update("UPDATE student_grades SET status = 'DRAFT' WHERE schedule_id = ?", scheduleId);
    }

    // ==========================================
    // ADMIN TOOLS
    // ==========================================
    public List<Map<String, Object>> getPendingClassSubmissions() {
        return formatClassList(db.queryForList("SELECT s.*, c.description, u.real_name as faculty_name FROM class_schedules s JOIN curriculum_catalog c ON s.course_code = c.course_code LEFT JOIN sys_users u ON s.faculty_id = u.user_id WHERE s.status = 'PENDING_APPROVAL'"));
    }

    public List<Map<String, Object>> getPendingApprovals() {
        return db.queryForList("SELECT r.request_id, r.student_name, r.faculty_name AS faculty, r.requested_grade AS new_grade, r.reason, COALESCE(CAST(g.semestral_grade AS CHAR), 'N/A') AS old_grade, r.course_code FROM grade_change_requests r LEFT JOIN student_grades g ON r.grade_id = g.grade_id WHERE r.status = 'PENDING' ORDER BY r.request_date DESC");
    }

    @Transactional
    public void approveGradeChange(int requestId) {
        Map<String, Object> req = db.queryForMap("SELECT * FROM grade_change_requests WHERE request_id = ?", requestId);
        db.update("UPDATE student_grades SET status = 'DRAFT' WHERE grade_id = ?", req.get("grade_id"));
        db.update("UPDATE grade_change_requests SET status = 'APPROVED' WHERE request_id = ?", requestId);
    }

    @Transactional
    public void requestGradeChange(int gradeId, String newGrade, String reason, int facultyId) {
        Map<String, Object> info = db.queryForMap("SELECT g.student_name, s.course_code FROM student_grades g LEFT JOIN class_schedules s ON g.schedule_id = s.schedule_id WHERE g.grade_id = ?", gradeId);
        String facultyName = db.queryForObject("SELECT real_name FROM sys_users WHERE user_id = ?", String.class, facultyId);
        db.update("INSERT INTO grade_change_requests (grade_id, student_name, course_code, faculty_name, requested_grade, reason, status) VALUES (?, ?, ?, ?, ?, ?, 'PENDING')", gradeId, info.get("student_name"), info.get("course_code"), facultyName, newGrade, reason);
    }

    public void toggleUserStatus(int uid, boolean a) { 
        db.update("UPDATE sys_users SET is_active=? WHERE user_id=?", a, uid); 
    }
    
    public void resetPassword(int uid) { 
        db.update("UPDATE sys_users SET password=? WHERE user_id=?", org.mindrot.jbcrypt.BCrypt.hashpw("1234", org.mindrot.jbcrypt.BCrypt.gensalt()), uid); 
    }
    
    public String getUsernameFromId(int uid) { 
        return db.queryForObject("SELECT username FROM sys_users WHERE user_id=?", String.class, uid); 
    }
    
    public List<Map<String, Object>> getAllUsers() { 
        return db.queryForList("SELECT * FROM sys_users"); 
    }
    
    public void createUser(String u, String r, String role, String p, List<String> perm) { 
        String h = org.mindrot.jbcrypt.BCrypt.hashpw("1234", org.mindrot.jbcrypt.BCrypt.gensalt()); 
        db.update("INSERT INTO sys_users (username,password,real_name,role,program_code,granted_permissions,is_active) VALUES (?,?,?,?,?,?,1)", u,h,r,role,p, perm!=null?perm.toString():"[]"); 
    }
    
    public void updateUserPermissions(int uid, String role, List<String> perm) { 
        db.update("UPDATE sys_users SET role=?, granted_permissions=? WHERE user_id=?", role, perm!=null?perm.toString():"[]", uid); 
    }
    
    public void deleteUser(int uid) { 
        db.update("DELETE FROM sys_users WHERE user_id=?", uid); 
    }
    
    public List<Map<String, Object>> getAllClassesAdmin() { 
        return formatClassList(db.queryForList("CALL sp_GetAllClasses()")); 
    }

    private String formatTime(Object t) {
        if (t == null) return "TBA";
        int timeInt = ((Number) t).intValue();
        int h = timeInt / 100;
        int m = timeInt % 100;
        return String.format("%d:%02d %s", (h > 12 ? h - 12 : (h == 0 ? 12 : h)), m, (h >= 12 ? "PM" : "AM"));
    }

    private List<Map<String, Object>> formatClassList(List<Map<String, Object>> l) {
        for (Map<String, Object> r : l) {
            r.put("pretty_schedule", r.get("day") + " " + formatTime(r.get("start_time")) + "-" + formatTime(r.get("end_time")));
        }
        return l;
    }
}