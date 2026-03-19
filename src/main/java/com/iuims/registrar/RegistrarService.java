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
            db.execute("CREATE TABLE IF NOT EXISTS student_ledger (ledger_id INT AUTO_INCREMENT PRIMARY KEY, student_id INT, transaction_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP, transaction_type VARCHAR(20), description VARCHAR(255), debit DECIMAL(10,2) DEFAULT 0.00, credit DECIMAL(10,2) DEFAULT 0.00)");

            try { db.execute("ALTER TABLE class_schedules ADD COLUMN is_unlocked TINYINT DEFAULT 0"); } catch (Exception ignored) {}
            try { db.execute("ALTER TABLE curriculum_catalog ADD COLUMN has_lab TINYINT DEFAULT 0"); } catch (Exception ignored) {}
            try { db.execute("ALTER TABLE student_grades ADD COLUMN lab_prelim DECIMAL(5,2) DEFAULT 0.00, ADD COLUMN lab_midterm DECIMAL(5,2) DEFAULT 0.00, ADD COLUMN lab_final DECIMAL(5,2) DEFAULT 0.00, ADD COLUMN lab_semestral_grade DECIMAL(5,2) DEFAULT 0.00, ADD COLUMN lab_remarks VARCHAR(20) DEFAULT 'Ongoing'"); } catch (Exception ignored) {}
            try { db.execute("ALTER TABLE curriculum_catalog ADD COLUMN prerequisite VARCHAR(20) DEFAULT 'NONE'"); } catch (Exception ignored) {}
            
            // THE FIX: Aggressive Ghost Buster forced through Safe Updates
            try {
                db.execute("SET SQL_SAFE_UPDATES = 0");
                db.execute("DELETE FROM student_grades WHERE status = 'DRAFT' AND NOT EXISTS (SELECT 1 FROM enrollment_details e WHERE e.student_id = student_grades.student_id AND e.schedule_id = student_grades.schedule_id)");
                db.execute("SET SQL_SAFE_UPDATES = 1");
                System.out.println(">> SYSTEM: Ghost records cleaned from Student Portal.");
            } catch (Exception e) {
                System.err.println(">> SYSTEM GHOST BUSTER FAILED: " + e.getMessage());
            }
            
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

    private void chargeMiscFeeIfNotCharged(int sid) {
        int count = db.queryForObject("SELECT COUNT(*) FROM student_ledger WHERE student_id = ? AND description = 'Miscellaneous Fees'", Integer.class, sid);
        if (count == 0) {
            db.update("INSERT INTO student_ledger (student_id, transaction_type, description, debit) VALUES (?, 'ASSESSMENT', 'Miscellaneous Fees', 5500.00)", sid);
        }
    }

    public List<Map<String, Object>> getStudentLedger(int sid) {
        List<Map<String, Object>> records = db.queryForList("SELECT * FROM student_ledger WHERE student_id = ? ORDER BY transaction_date ASC, ledger_id ASC", sid);
        double runningBalance = 0.0;
        for (Map<String, Object> r : records) {
            double debit = ((Number) (r.get("debit") != null ? r.get("debit") : 0)).doubleValue();
            double credit = ((Number) (r.get("credit") != null ? r.get("credit") : 0)).doubleValue();
            runningBalance += (debit - credit);
            r.put("running_balance", runningBalance);
        }
        return records;
    }

    public void submitPayment(int studentId, double amount, String referenceNumber, String base64Image) {
        db.update("INSERT INTO payments (student_id, amount, reference_number, receipt_image) VALUES (?, ?, ?, ?)", studentId, amount, referenceNumber, base64Image);
    }

    public List<Map<String, Object>> getPendingPayments() {
        return db.queryForList("SELECT p.*, u.real_name, u.username FROM payments p JOIN sys_users u ON p.student_id = u.user_id WHERE p.status = 'PENDING' ORDER BY p.payment_date ASC");
    }

    public List<Map<String, Object>> getStudentPayments(int studentId) {
        return db.queryForList("SELECT * FROM payments WHERE student_id = ? ORDER BY payment_date DESC", studentId);
    }

    @Transactional
    public void verifyPayment(int paymentId) {
        Map<String, Object> payInfo = db.queryForMap("SELECT student_id, amount, reference_number FROM payments WHERE payment_id = ?", paymentId);
        db.update("UPDATE payments SET status = 'VERIFIED' WHERE payment_id = ?", paymentId);
        Integer studentId = (Integer) payInfo.get("student_id");
        if (studentId != null) {
            db.update("INSERT INTO student_ledger (student_id, transaction_type, description, credit) VALUES (?, 'PAYMENT', ?, ?)", studentId, "Payment (Ref: " + payInfo.get("reference_number") + ")", payInfo.get("amount"));
            int enrolledCount = db.queryForObject("SELECT COUNT(*) FROM enrollment_details WHERE student_id = ?", Integer.class, studentId);
            if (enrolledCount == 0) autoEnrollStudent(studentId);
        }
    }

    @Transactional
    public void autoEnrollStudent(int studentId) {
        Map<String, Object> student = db.queryForMap("SELECT year_level, real_name FROM sys_users WHERE user_id = ?", studentId);
        int yearLevel = (int) student.get("year_level");
        String realName = (String) student.get("real_name");

        chargeMiscFeeIfNotCharged(studentId);
        String sql = "SELECT s.schedule_id, c.course_code, c.units FROM class_schedules s JOIN curriculum_catalog c ON s.course_code = c.course_code WHERE c.year_level = ? AND s.status = 'OPEN'";
        List<Map<String, Object>> matchingSchedules = db.queryForList(sql, yearLevel);

        for (Map<String, Object> sched : matchingSchedules) {
            try {
                int schedId = (int) sched.get("schedule_id");
                int count = db.queryForObject("SELECT COUNT(*) FROM enrollment_details WHERE student_id = ? AND schedule_id = ?", Integer.class, studentId, schedId);
                if (count == 0) {
                    db.update("INSERT INTO enrollment_details (student_id, schedule_id) VALUES (?, ?)", studentId, schedId);
                    db.update("INSERT INTO student_grades (student_id, schedule_id, student_name, status) VALUES (?, ?, ?, 'DRAFT')", studentId, schedId, realName);
                    double cost = ((Number) sched.get("units")).doubleValue() * 1500.0;
                    db.update("INSERT INTO student_ledger (student_id, transaction_type, description, debit) VALUES (?, 'ASSESSMENT', ?, ?)", studentId, "Added Subject: " + sched.get("course_code"), cost);
                }
            } catch (Exception e) {}
        }
    }

    public void rejectPayment(int paymentId) {
        db.update("UPDATE payments SET status = 'REJECTED' WHERE payment_id = ?", paymentId);
    }

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

    public List<Map<String, Object>> searchStudentsBySurname(String q) {
        String sql = "SELECT username, real_name FROM sys_users WHERE (role = 'Student' OR role = 'student') AND LOWER(real_name) LIKE LOWER(?) LIMIT 10";
        return db.queryForList(sql, "%" + q + "%");
    }

    public Map<String, Object> findStudentByIdOrName(String q) { 
        try { return db.queryForMap("SELECT * FROM sys_users WHERE username = ?", q); } catch (Exception e) { return null; }
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
        } catch (Exception e) { return "ERROR"; }
    }

    public List<Map<String, Object>> getPendingApplications() {
        return db.queryForList("SELECT * FROM admission_applications WHERE status = 'PENDING'");
    }

    public Map<String, Object> login(String u, String p) {
        try {
            Map<String, Object> user = db.queryForMap("SELECT * FROM sys_users WHERE username=?", u);
            if (org.mindrot.jbcrypt.BCrypt.checkpw(p, (String) user.get("password"))) return user;
        } catch (Exception e) {}
        return null;
    }

    public List<Map<String, Object>> getStudentLoad(int sid) {
        try { return formatClassList(db.queryForList("CALL sp_GetStudentLoad(?)", sid)); } catch (Exception e) { return new ArrayList<>(); }
    }

    public int getTotalUnits(int sid) {
        try { 
            String sql = "SELECT COALESCE(SUM(c.units),0) FROM enrollment_details e JOIN class_schedules s ON e.schedule_id=s.schedule_id JOIN curriculum_catalog c ON s.course_code=c.course_code WHERE e.student_id=?";
            return db.queryForObject(sql, Integer.class, sid); 
        } catch (Exception e) { return 0; }
    }

    public Map<String, Object> calculateAssessment(int sid) {
        Map<String, Object> m = new HashMap<>();
        
        Double totalDebit = db.queryForObject("SELECT SUM(debit) FROM student_ledger WHERE student_id = ?", Double.class, sid);
        if (totalDebit == null) totalDebit = 0.0;
        
        Double totalCredit = db.queryForObject("SELECT SUM(credit) FROM student_ledger WHERE student_id = ?", Double.class, sid);
        if (totalCredit == null) totalCredit = 0.0;

        Double refunds = db.queryForObject("SELECT SUM(credit) FROM student_ledger WHERE student_id = ? AND transaction_type = 'REFUND'", Double.class, sid);
        if (refunds == null) refunds = 0.0;

        Double misc = db.queryForObject("SELECT SUM(debit) FROM student_ledger WHERE student_id = ? AND description = 'Miscellaneous Fees'", Double.class, sid);
        if (misc == null) misc = 0.0;

        double activeAssessment = totalDebit - refunds;
        double tuition = activeAssessment - misc;
        if (tuition < 0) tuition = 0.0;
        
        double balance = totalDebit - totalCredit;

        m.put("tuition_fee", tuition);
        m.put("misc_fee", misc);
        m.put("total_assessment", activeAssessment);
        m.put("total_paid", totalCredit - refunds);
        m.put("balance", balance);
        
        m.put("tuition_fee_fmt", String.format("%,.2f", tuition));
        m.put("misc_fee_fmt", String.format("%,.2f", misc));
        m.put("total_assessment_fmt", String.format("%,.2f", activeAssessment));
        m.put("total_paid_fmt", String.format("%,.2f", totalCredit - refunds));
        m.put("balance_fmt", String.format("%,.2f", Math.max(0, balance)));
        
        return m;
    }

    public List<Map<String, Object>> getAnalyzedOfferings(int sid) {
        String sql = "SELECT s.*, c.description, c.units, c.has_lab, c.prerequisite, u.real_name as faculty_name FROM class_schedules s JOIN curriculum_catalog c ON s.course_code = c.course_code LEFT JOIN sys_users u ON s.faculty_id = u.user_id ORDER BY s.course_code";
        List<Map<String, Object>> classes = formatClassList(db.queryForList(sql));
        
        List<String> enrolledCourses = db.queryForList("SELECT s.course_code FROM enrollment_details e JOIN class_schedules s ON e.schedule_id = s.schedule_id WHERE e.student_id = ?", String.class, sid);
        List<String> passedCourses = db.queryForList("SELECT cs.course_code FROM student_grades g JOIN class_schedules cs ON g.schedule_id = cs.schedule_id WHERE g.student_id = ? AND g.remarks = 'Passed'", String.class, sid);
        List<Map<String, Object>> currentSchedules = db.queryForList("SELECT s.day, s.start_time, s.end_time, s.course_code FROM enrollment_details e JOIN class_schedules s ON e.schedule_id = s.schedule_id WHERE e.student_id = ?", sid);
        
        int currentUnits = getTotalUnits(sid);

        for (Map<String, Object> c : classes) {
            String courseCode = (String) c.get("course_code");
            int schedId = (int) c.get("schedule_id");
            int units = c.get("units") != null ? ((Number) c.get("units")).intValue() : 3;
            String prereq = (String) c.get("prerequisite");

            boolean isDisabled = false;
            String reason = "";

            if (prereq != null && !prereq.trim().equalsIgnoreCase("NONE") && !prereq.trim().isEmpty()) {
                boolean hasPassed = false;
                String normalizedPrereq = prereq.replaceAll("\\s+", "").toUpperCase();
                for (String pc : passedCourses) {
                    if (pc.replaceAll("\\s+", "").toUpperCase().equals(normalizedPrereq)) { 
                        hasPassed = true; break; 
                    }
                }
                if (!hasPassed) {
                    isDisabled = true;
                    reason = "Needs " + prereq;
                }
            }
            
            if (!isDisabled && passedCourses.contains(courseCode)) {
                isDisabled = true;
                reason = "Already Passed";
            }
            
            if (!isDisabled && enrolledCourses.contains(courseCode)) {
                isDisabled = true;
                reason = "Currently Enrolled";
            }
            
            if (!isDisabled) {
                String newDay = (String) c.get("day");
                int newStart = ((Number) c.get("start_time")).intValue();
                int newEnd = ((Number) c.get("end_time")).intValue();
                
                for (Map<String, Object> cur : currentSchedules) {
                    String curDay = (String) cur.get("day");
                    int curStart = ((Number) cur.get("start_time")).intValue();
                    int curEnd = ((Number) cur.get("end_time")).intValue();
                    
                    if (isTimeConflict(newDay, newStart, newEnd, curDay, curStart, curEnd)) {
                        isDisabled = true;
                        reason = "Time Conflict";
                        break;
                    }
                }
            }

            if (!isDisabled && (currentUnits + units > 24)) {
                isDisabled = true;
                reason = "Exceeds 24 Units";
            }

            if (!isDisabled) {
                int maxSlots = c.get("max_slots") != null ? (int) c.get("max_slots") : 40;
                int enrolledCount = db.queryForObject("SELECT COUNT(*) FROM enrollment_details WHERE schedule_id = ?", Integer.class, schedId);
                if (enrolledCount >= maxSlots) {
                    isDisabled = true;
                    reason = "Class Full";
                }
            }

            c.put("is_disabled", isDisabled);
            c.put("reason_msg", isDisabled ? reason : "");
        }
        return classes;
    }

    public Map<String, List<Map<String, Object>>> getStudentAcademicHistory(int sid) {
        String sql = "SELECT s.course_code, s.description, g.prelim, g.midterm, g.final, g.semestral_grade, g.remarks, s.curriculum_year FROM student_grades g JOIN class_schedules cs ON g.schedule_id = cs.schedule_id JOIN curriculum_catalog s ON cs.course_code = s.course_code WHERE g.student_id = ? AND g.status = 'SUBMITTED' ORDER BY s.curriculum_year DESC";
        List<Map<String, Object>> raw = db.queryForList(sql, sid);
        Map<String, List<Map<String, Object>>> h = new LinkedHashMap<>();
        
        for (Map<String, Object> r : raw) {
            double p = r.get("prelim") != null ? ((Number)r.get("prelim")).doubleValue() : 0.0;
            double m = r.get("midterm") != null ? ((Number)r.get("midterm")).doubleValue() : 0.0;
            double f = r.get("final") != null ? ((Number)r.get("final")).doubleValue() : 0.0;
            double sg = r.get("semestral_grade") != null ? ((Number)r.get("semestral_grade")).doubleValue() : 0.0;

            r.put("prelim_score", p > 0 ? String.valueOf(p) : "-");
            r.put("midterm_score", m > 0 ? String.valueOf(m) : "-");
            r.put("finals_score", f > 0 ? String.valueOf(f) : "-");
            r.put("semestral_score", sg > 0 ? String.valueOf(sg) : "-");

            r.put("prelim_point", p > 0 ? String.format("%.2f", convertToPointGrade(p)) : "");
            r.put("midterm_point", m > 0 ? String.format("%.2f", convertToPointGrade(m)) : "");
            r.put("final_point", f > 0 ? String.format("%.2f", convertToPointGrade(f)) : "");

            String cy = r.get("curriculum_year") != null ? r.get("curriculum_year").toString() : "Unknown";
            h.computeIfAbsent(cy, k -> new ArrayList<>()).add(r);
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
            String curDay = (String) current.get("day");
            int currStart = ((Number) current.get("start_time")).intValue();
            int currEnd = ((Number) current.get("end_time")).intValue();
            
            if (isTimeConflict(newDay, newStart, newEnd, curDay, currStart, currEnd)) {
                return "Conflict detected with " + current.get("course_code");
            }
        }
        return null;
    }

    @Transactional
    public String addSubjectDirectly(int sid, int schedId) {
        try {
            int count = db.queryForObject("SELECT COUNT(*) FROM enrollment_details WHERE student_id = ? AND schedule_id = ?", Integer.class, sid, schedId);
            if (count > 0) return "CONFLICT: Student is already enrolled in this subject.";

            String conflict = checkScheduleConflict(sid, schedId);
            if (conflict != null) return "CONFLICT: " + conflict;

            Map<String, Object> classInfo = db.queryForMap("SELECT s.max_slots, c.course_code, c.units, c.prerequisite FROM class_schedules s JOIN curriculum_catalog c ON s.course_code = c.course_code WHERE s.schedule_id = ?", schedId);
            
            String prereq = (String) classInfo.get("prerequisite");
            if (prereq != null && !prereq.trim().equalsIgnoreCase("NONE") && !prereq.trim().isEmpty()) {
                String normalizedPrereq = prereq.replaceAll("\\s+", "").toUpperCase();
                int passed = db.queryForObject("SELECT COUNT(*) FROM student_grades g JOIN class_schedules cs ON g.schedule_id = cs.schedule_id WHERE g.student_id = ? AND REPLACE(UPPER(cs.course_code), ' ', '') = ? AND g.remarks = 'Passed'", Integer.class, sid, normalizedPrereq);
                if (passed == 0) return "CONFLICT: Student has not passed prerequisite (" + prereq + ").";
            }

            int maxSlots = (int) classInfo.get("max_slots");
            int enrolledCount = db.queryForObject("SELECT COUNT(*) FROM enrollment_details WHERE schedule_id = ?", Integer.class, schedId);
            if (enrolledCount >= maxSlots) {
                return "CONFLICT: Class " + classInfo.get("course_code") + " is already FULL.";
            }

            int currentUnits = getTotalUnits(sid);
            int newClassUnits = (int) classInfo.get("units");
            if ((currentUnits + newClassUnits) > 24) {
                return "CONFLICT: Adding this subject exceeds maximum load of 24 units.";
            }

            db.update("INSERT INTO enrollment_details (student_id, schedule_id) VALUES (?, ?)", sid, schedId);
            
            String realName = db.queryForObject("SELECT real_name FROM sys_users WHERE user_id = ?", String.class, sid);
            db.update("INSERT INTO student_grades (student_id, schedule_id, student_name, status) VALUES (?, ?, ?, 'DRAFT')", sid, schedId, realName);

            chargeMiscFeeIfNotCharged(sid);
            double cost = newClassUnits * 1500.0;
            db.update("INSERT INTO student_ledger (student_id, transaction_type, description, debit) VALUES (?, 'ASSESSMENT', ?, ?)", sid, "Added Subject: " + classInfo.get("course_code"), cost);

            return "Success";
        } catch (Exception e) { 
            return "Error processing enrollment."; 
        }
    }
    
    @Transactional 
    public String dropSubjectDirectly(int sid, int schedId) {
        Map<String, Object> classInfo = db.queryForMap("SELECT c.course_code, c.units FROM class_schedules s JOIN curriculum_catalog c ON s.course_code = c.course_code WHERE s.schedule_id = ?", schedId);
        
        // 1. Remove official enrollment
        db.update("DELETE FROM enrollment_details WHERE student_id=? AND schedule_id=?", sid, schedId);
        
        // 2. THE FIX: Force Safe Updates off so MySQL deletes the ghost grade permanently
        db.execute("SET SQL_SAFE_UPDATES = 0");
        db.update("DELETE FROM student_grades WHERE student_id=? AND schedule_id=? AND status='DRAFT'", sid, schedId);
        db.execute("SET SQL_SAFE_UPDATES = 1");
        
        // 3. Issue the refund
        double refund = ((Number) classInfo.get("units")).doubleValue() * 1500.0;
        db.update("INSERT INTO student_ledger (student_id, transaction_type, description, credit) VALUES (?, 'REFUND', ?, ?)", sid, "Refund (Dropped): " + classInfo.get("course_code"), refund);

        return "Dropped";
    }

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
        } else if (f == 0) {
            pointGrade = 0.0;
            remarks = "Ongoing";
        } else if (p == 0 || m == 0) {
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
        if (s == null || s.trim().isEmpty()) { return 0.0; }
        try { return Double.parseDouble(s); } catch (Exception e) { return 0.0; }
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
            
            g.put("finals_score", g.get("final"));

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
        
        String day = (String) map.get("day");
        String st = formatTime(map.get("start_time"));
        String et = formatTime(map.get("end_time"));
        
        if (day == null || day.trim().isEmpty() || day.equalsIgnoreCase("TBA") || st.equals("TBA")) {
            map.put("pretty_schedule", "TBA (Asynchronous)");
        } else {
            map.put("pretty_schedule", day + " " + st + "-" + et);
        }
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
        if (timeInt == 0) return "TBA";
        int h = timeInt / 100;
        int m = timeInt % 100;
        return String.format("%d:%02d %s", (h > 12 ? h - 12 : (h == 0 ? 12 : h)), m, (h >= 12 ? "PM" : "AM"));
    }

    private List<Map<String, Object>> formatClassList(List<Map<String, Object>> l) {
        for (Map<String, Object> r : l) {
            String day = (String) r.get("day");
            String st = formatTime(r.get("start_time"));
            String et = formatTime(r.get("end_time"));
            
            if (day == null || day.trim().isEmpty() || day.equalsIgnoreCase("TBA") || st.equals("TBA")) {
                r.put("pretty_schedule", "TBA (Asynchronous)");
            } else {
                r.put("pretty_schedule", day + " " + st + "-" + et);
            }
        }
        return l;
    }

    public Map<String, Object> findPendingApplicant(String q) {
        try {
            String sql = "SELECT * FROM admission_applications WHERE status = 'PENDING' AND (LOWER(applicant_id) LIKE LOWER(?) OR LOWER(full_name) LIKE LOWER(?)) LIMIT 1";
            return db.queryForMap(sql, "%" + q + "%", "%" + q + "%");
        } catch (Exception e) {
            return null;
        }
    }

    public int getTotalStudentCount() {
        return db.queryForObject("SELECT COUNT(*) FROM sys_users WHERE role LIKE 'Student'", Integer.class);
    }

    public List<Map<String, Object>> searchApplicantsByName(String q) {
        String sql = "SELECT applicant_id, full_name FROM admission_applications WHERE status = 'PENDING' AND LOWER(full_name) LIKE LOWER(?) LIMIT 10";
        return db.queryForList(sql, "%" + q + "%");
    }

    private boolean isTimeConflict(String day1, int start1, int end1, String day2, int start2, int end2) {
        if (day1 == null || day2 == null) return false;
        if (start1 == 0 && end1 == 0) return false; 
        if (start2 == 0 && end2 == 0) return false; 
        
        boolean dayOverlap = false;
        String[] d1 = day1.split(",");
        String[] d2 = day2.split(",");
        
        for (String a : d1) {
            for (String b : d2) {
                String da = normalizeDay(a);
                String db = normalizeDay(b);
                if (da.equals(db) && !da.isEmpty()) {
                    dayOverlap = true; 
                    break;
                }
            }
            if (dayOverlap) break;
        }
        return dayOverlap && (start1 < end2 && end1 > start2);
    }

    private String normalizeDay(String d) {
        if (d == null) return "";
        d = d.trim().toUpperCase();
        if (d.contains("MON") || d.equals("M")) return "MON";
        if (d.contains("TUE") || d.equals("T")) return "TUE";
        if (d.contains("WED") || d.equals("W")) return "WED";
        if (d.contains("THU") || d.equals("TH")) return "THU";
        if (d.contains("FRI") || d.equals("F")) return "FRI";
        if (d.contains("SAT")) return "SAT";
        if (d.contains("SUN")) return "SUN";
        return d;
    }
}