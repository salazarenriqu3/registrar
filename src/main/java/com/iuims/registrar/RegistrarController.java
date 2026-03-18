package com.iuims.registrar;

import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Base64;
import java.util.List;
import java.util.Map;

@Controller
public class RegistrarController {

    @Autowired
    private RegistrarService service;

    @GetMapping("/")
    public String dashboard(HttpSession s, Model m) {
        Map<String, Object> user = (Map<String, Object>) s.getAttribute("currentUser");
        
        if (user == null) {
            return "redirect:/login";
        }
        
        String role = (String) user.get("role");
        if ("Faculty".equals(role)) {
            return "redirect:/grades";
        }
        if ("Student".equals(role)) {
            return "redirect:/enrollment";
        }
        
        m.addAttribute("pendingApps", service.getPendingApplications().size());
        m.addAttribute("pendingPayments", service.getPendingPayments().size());
        m.addAttribute("user", user);
        return "dashboard";
    }

    @GetMapping("/login")
    public String loginPage() {
        return "login";
    }

    @PostMapping("/login")
    public String doLogin(@RequestParam String username, @RequestParam String password, HttpSession s, Model m) {
        Map<String, Object> user = service.login(username, password);
        if (user != null) {
            s.setAttribute("currentUser", user);
            return "redirect:/";
        }
        m.addAttribute("error", "Invalid Credentials");
        return "login";
    }

    @GetMapping("/logout")
    public String logout(HttpSession s) {
        s.invalidate();
        return "redirect:/login";
    }

    // ==========================================
    // STUDENT PORTAL 
    // ==========================================
    
    @GetMapping("/enrollment")
    public String studentEnrollment(HttpSession s, Model m) {
        Map<String, Object> u = (Map<String, Object>) s.getAttribute("currentUser");
        if (u == null || !"Student".equals(u.get("role"))) return "redirect:/login";
        
        int sid = (int) u.get("user_id");
        m.addAttribute("classes", service.getAnalyzedOfferings(sid));
        return "enrollment"; 
    }

    @GetMapping("/my-grades")
    public String studentMyGrades(HttpSession s, Model m) {
        Map<String, Object> u = (Map<String, Object>) s.getAttribute("currentUser");
        if (u == null || !"Student".equals(u.get("role"))) return "redirect:/login";
        
        int sid = (int) u.get("user_id");
        m.addAttribute("academicHistory", service.getStudentAcademicHistory(sid));
        return "student_grades"; 
    }

    @GetMapping("/student/finance")
    public String studentFinance(HttpSession s, Model m) {
        Map<String, Object> u = (Map<String, Object>) s.getAttribute("currentUser");
        if (u == null || !"Student".equals(u.get("role"))) return "redirect:/login";
        
        int sid = (int) u.get("user_id");
        m.addAttribute("finance", service.calculateAssessment(sid));
        m.addAttribute("ledger", service.getStudentLedger(sid)); // NEW: Fetching True Ledger
        return "student_finance"; 
    }

    @GetMapping("/my-load")
    public String studentMyLoad(HttpSession s, Model m) {
        Map<String, Object> u = (Map<String, Object>) s.getAttribute("currentUser");
        if (u == null || !"Student".equals(u.get("role"))) return "redirect:/login";
        
        int sid = (int) u.get("user_id");
        m.addAttribute("student", u);
        m.addAttribute("studentLoad", service.getStudentLoad(sid));
        m.addAttribute("totalUnits", service.getTotalUnits(sid));
        m.addAttribute("finance", service.calculateAssessment(sid));
        return "student_cor"; 
    }

    @PostMapping("/student/submit-payment")
    public String submitPayment(
            @RequestParam double amount, 
            @RequestParam String referenceNumber, 
            @RequestParam("receiptFile") MultipartFile file, 
            HttpSession s, 
            RedirectAttributes redir) {
        
        Map<String, Object> u = (Map<String, Object>) s.getAttribute("currentUser");
        if (u != null && "Student".equals(u.get("role"))) {
            try {
                String base64Image = "data:" + file.getContentType() + ";base64," + Base64.getEncoder().encodeToString(file.getBytes());
                service.submitPayment((int) u.get("user_id"), amount, referenceNumber, base64Image);
                redir.addFlashAttribute("successMsg", "Payment submitted successfully! Awaiting verification by Cashier.");
            } catch (Exception e) {
                redir.addFlashAttribute("errorMsg", "Failed to upload image. Please try again.");
            }
        }
        return "redirect:/student/finance";
    }

    // ==========================================
    // ADMIN ENROLLMENT & PAYMENTS
    // ==========================================
    @GetMapping("/admin/payments")
    public String adminPayments(HttpSession s, Model m) {
        if (s.getAttribute("currentUser") == null) return "redirect:/login";
        m.addAttribute("pendingPayments", service.getPendingPayments());
        return "admin_payments";
    }

    @PostMapping("/admin/verify-payment")
    public String verifyPayment(@RequestParam int paymentId) {
        service.verifyPayment(paymentId);
        return "redirect:/admin/payments";
    }

    @PostMapping("/admin/reject-payment")
    public String rejectPayment(@RequestParam int paymentId) {
        service.rejectPayment(paymentId);
        return "redirect:/admin/payments";
    }

    @GetMapping("/admin/enrollment")
    public String adminEnrollmentHub(@RequestParam(required=false) String username, @RequestParam(required=false) String errorMsg, Model model, HttpSession session) {
        if (session.getAttribute("currentUser") == null) return "redirect:/login";
        if (errorMsg != null) model.addAttribute("errorMsg", errorMsg);
        
        if (username != null && !username.trim().isEmpty()) {
            Map<String, Object> s = service.findStudentByIdOrName(username);
            if (s != null) {
                int sid = (int) s.get("user_id");
                model.addAttribute("student", s);
                model.addAttribute("classes", service.getAnalyzedOfferings(sid)); 
                model.addAttribute("studentLoad", service.getStudentLoad(sid));   
                model.addAttribute("totalUnits", service.getTotalUnits(sid));
                model.addAttribute("finance", service.calculateAssessment(sid));
            } else {
                model.addAttribute("message", "Student not found.");
            }
        }
        return "admin_enrollment";
    }

    @PostMapping("/admin/process-enrollment")
    public String adminProcessEnrollment(@RequestParam int studentId, @RequestParam int scheduleId, RedirectAttributes redir) {
        String result = service.addSubjectDirectly(studentId, scheduleId);
        String username = service.getUsernameFromId(studentId);
        
        if (result.startsWith("CONFLICT:")) redir.addAttribute("errorMsg", result);
        redir.addAttribute("username", username);
        return "redirect:/admin/enrollment";
    }

    @PostMapping("/admin/enrollment-drop")
    public String adminEnrollmentDrop(@RequestParam int studentId, @RequestParam int scheduleId, RedirectAttributes redir) {
        service.dropSubjectDirectly(studentId, scheduleId);
        redir.addAttribute("username", service.getUsernameFromId(studentId));
        return "redirect:/admin/enrollment";
    }

    // ==========================================
    // ADMIN ADMISSION & STUDENT MANAGER
    // ==========================================
    @GetMapping("/admin/admission-acceptance")
    public String admissionAcceptance(HttpSession session, Model model) {
        if (session.getAttribute("currentUser") == null) return "redirect:/login";
        model.addAttribute("applicants", service.getPendingApplications());
        return "admin_admission_acceptance";
    }

    @PostMapping("/admin/process-admission")
    public String processAdmission(@RequestParam String applicantId, @RequestParam String finalProgram, @RequestParam String yearLevel, @RequestParam String curriculum, @RequestParam String email, @RequestParam String phone, @RequestParam String address, @RequestParam String birthdate, @RequestParam String sex) {
        service.acceptAdmission(applicantId, finalProgram, yearLevel, curriculum, email, phone, address, birthdate, sex);
        return "redirect:/admin/admission-acceptance?success=true";
    }

    @GetMapping("/admin/student-manager")
    public String manageStudentSearch(@RequestParam(required=false) String username, @RequestParam(required=false) String errorMsg, Model model, HttpSession session) {
        if (session.getAttribute("currentUser") == null) return "redirect:/login";
        if (errorMsg != null) model.addAttribute("message", errorMsg);
        
        if (username != null && !username.trim().isEmpty()) {
            Map<String, Object> s = service.findStudentByIdOrName(username);
            if (s != null) {
                int sid = (int) s.get("user_id");
                model.addAttribute("student", s);
                model.addAttribute("studentLoad", service.getStudentLoad(sid));
                model.addAttribute("totalUnits", service.getTotalUnits(sid));
                model.addAttribute("analyzedClasses", service.getAnalyzedOfferings(sid));
                model.addAttribute("academicHistory", service.getStudentAcademicHistory(sid));
                model.addAttribute("finance", service.calculateAssessment(sid));
            } else {
                model.addAttribute("message", "Student not found.");
            }
        }
        return "admin_student_manager";
    }
    
    @GetMapping("/api/search-students")
    @ResponseBody
    public List<Map<String, Object>> searchApi(@RequestParam String query) {
        return service.searchStudentsBySurname(query);
    }
    
    @PostMapping("/admin/enroll")
    public String adminEnroll(@RequestParam int studentId, @RequestParam int scheduleId) {
        String result = service.addSubjectDirectly(studentId, scheduleId);
        String username = service.getUsernameFromId(studentId);
        
        if (result.startsWith("CONFLICT:")) {
            return "redirect:/admin/student-manager?username=" + username + "&errorMsg=" + result;
        }
        return "redirect:/admin/student-manager?username=" + username;
    }
    
    @PostMapping("/admin/drop")
    public String adminDrop(@RequestParam int studentId, @RequestParam int scheduleId) {
        service.dropSubjectDirectly(studentId, scheduleId);
        return "redirect:/admin/student-manager?username=" + service.getUsernameFromId(studentId);
    }

    @PostMapping("/admin/toggle-status")
    public String toggleStatus(@RequestParam int userId, @RequestParam boolean isActive) {
        service.toggleUserStatus(userId, isActive);
        return "redirect:/admin/student-manager";
    }

    @PostMapping("/admin/reset-password")
    public String resetPassword(@RequestParam int userId) {
        service.resetPassword(userId);
        return "redirect:/admin/student-manager";
    }

    // ==========================================
    // DOCUMENT PRINTING
    // ==========================================
    @GetMapping("/admin/print-cor")
    public String printCor(@RequestParam String username, Model model, HttpSession session) {
        if (session.getAttribute("currentUser") == null) return "redirect:/login";
        
        Map<String, Object> student = service.findStudentByIdOrName(username);
        if (student != null) {
            int sid = (int) student.get("user_id");
            model.addAttribute("student", student);
            model.addAttribute("studentLoad", service.getStudentLoad(sid));
            model.addAttribute("totalUnits", service.getTotalUnits(sid));
            model.addAttribute("finance", service.calculateAssessment(sid));
            return "print_cor";
        }
        return "redirect:/admin/student-manager";
    }

    @GetMapping("/admin/print-cog")
    public String printCog(@RequestParam String username, Model model, HttpSession session) {
        if (session.getAttribute("currentUser") == null) return "redirect:/login";
        
        Map<String, Object> student = service.findStudentByIdOrName(username);
        if (student != null) {
            model.addAttribute("student", student);
            model.addAttribute("academicHistory", service.getStudentAcademicHistory((int) student.get("user_id")));
            return "print_cog";
        }
        return "redirect:/admin/student-manager";
    }

    @GetMapping("/admin/print-tor")
    public String printTor(@RequestParam String username, Model model, HttpSession session) {
        if (session.getAttribute("currentUser") == null) return "redirect:/login";
        
        Map<String, Object> student = service.findStudentByIdOrName(username);
        if (student != null) {
            model.addAttribute("student", student);
            model.addAttribute("academicHistory", service.getStudentAcademicHistory((int) student.get("user_id")));
            return "print_tor";
        }
        return "redirect:/admin/student-manager";
    }

    // ==========================================
    // FACULTY GRADING
    // ==========================================
    @GetMapping("/grades")
    public String facultyMenu(HttpSession s, Model m) {
        Map<String,Object> u = (Map<String,Object>) s.getAttribute("currentUser");
        if(u == null) return "redirect:/login";
        m.addAttribute("classes", service.getFacultyClasses((int)u.get("user_id")));
        return "grades_menu";
    }
    
    @GetMapping("/grades/view/{id}")
    public String viewGradeSheet(@PathVariable int id, HttpSession s, Model m) {
        Map<String,Object> u = (Map<String,Object>) s.getAttribute("currentUser");
        if(u == null) return "redirect:/login";
        
        m.addAttribute("classInfo", service.getClassInfo(id));
        m.addAttribute("grades", service.getClassGrades(id));
        m.addAttribute("windows", service.getGradingWindows());
        m.addAttribute("user", u);
        m.addAttribute("readonly", !"Faculty".equals(u.get("role")));
        
        return "grades_sheet";
    }
    
    @PostMapping("/api/faculty/auto-save")
    @ResponseBody
    public Map<String, Object> autoSaveGrade(
            @RequestParam int gradeId, 
            @RequestParam String prelim, 
            @RequestParam String midterm, 
            @RequestParam String finals, 
            @RequestParam(defaultValue="LEC") String gradeType) {
        return service.saveGradeAsync(gradeId, prelim, midterm, finals, gradeType);
    }

    @PostMapping("/faculty/submit-class")
    public String submitClass(@RequestParam int scheduleId) {
        service.submitClassGrades(scheduleId);
        return "redirect:/grades/view/" + scheduleId;
    }

    @PostMapping("/faculty/unsubmit-class")
    public String unsubmitClass(@RequestParam int scheduleId) {
        service.unsubmitClassGrades(scheduleId);
        return "redirect:/grades/view/" + scheduleId;
    }

    @PostMapping("/faculty/request-change")
    public String requestChange(@RequestParam int gradeId, @RequestParam String newGrade, @RequestParam String reason, @RequestParam int scheduleId, HttpSession session) {
        Map<String, Object> user = (Map<String, Object>) session.getAttribute("currentUser");
        service.requestGradeChange(gradeId, newGrade, reason, (int)user.get("user_id"));
        return "redirect:/grades/view/" + scheduleId;
    }

    // ==========================================
    // ADMIN SETTINGS & MANAGEMENT
    // ==========================================
    @GetMapping("/admin/settings")
    public String adminSettings(HttpSession s, Model m) {
        if(s.getAttribute("currentUser") == null) return "redirect:/login";
        m.addAttribute("settings", service.getGradingWindows());
        return "admin_settings";
    }

    @PostMapping("/admin/save-settings")
    public String saveSettings(@RequestParam Map<String, String> params) {
        service.updateSettings(params);
        return "redirect:/admin/settings?success=true";
    }

    @PostMapping("/admin/toggle-unlock")
    public String toggleUnlock(@RequestParam int scheduleId, @RequestParam int unlockStatus) {
        service.toggleClassUnlock(scheduleId, unlockStatus);
        return "redirect:/admin/classes";
    }

    @GetMapping("/admin/approvals")
    public String adminApprovals(HttpSession s, Model m) {
        if(s.getAttribute("currentUser") == null) return "redirect:/login";
        m.addAttribute("pendingClasses", service.getPendingClassSubmissions());
        m.addAttribute("requests", service.getPendingApprovals());
        return "admin_approvals";
    }

    @PostMapping("/admin/approve-class")
    public String approveClass(@RequestParam int scheduleId) {
        service.finalizeClassGrades(scheduleId);
        return "redirect:/admin/approvals";
    }

    @PostMapping("/admin/approve-change")
    public String approveChange(@RequestParam int requestId) {
        service.approveGradeChange(requestId);
        return "redirect:/admin/approvals";
    }

    @GetMapping("/admin/users")
    public String adminUsers(Model m, HttpSession s) {
        if(s.getAttribute("currentUser") == null) return "redirect:/login";
        m.addAttribute("users", service.getAllUsers());
        return "admin_users";
    }

    @PostMapping("/create-user")
    public String createUser(@RequestParam String username, @RequestParam String realName, @RequestParam String role, @RequestParam(required=false) String program, @RequestParam(required=false) List<String> permissions) {
        service.createUser(username, realName, role, program, permissions);
        return "redirect:/admin/users";
    }

    @PostMapping("/admin/update-user")
    public String updateUser(@RequestParam int userId, @RequestParam String role, @RequestParam(required=false) List<String> permissions) {
        service.updateUserPermissions(userId, role, permissions);
        return "redirect:/admin/users";
    }

    @PostMapping("/admin/delete-user")
    public String deleteUser(@RequestParam int userId) {
        service.deleteUser(userId);
        return "redirect:/admin/users";
    }

    @GetMapping("/admin/classes")
    public String adminClasses(HttpSession s, Model m) {
        if(s.getAttribute("currentUser") == null) return "redirect:/login";
        m.addAttribute("classes", service.getAllClassesAdmin());
        return "admin_classes";
    }

    @GetMapping("/admin/classes/view/{id}")
    public String adminViewClass(@PathVariable int id, HttpSession s, Model m) {
        if(s.getAttribute("currentUser") == null) return "redirect:/login";
        m.addAttribute("classInfo", service.getClassInfo(id));
        m.addAttribute("grades", service.getClassGrades(id));
        m.addAttribute("windows", service.getGradingWindows());
        m.addAttribute("readonly", true);
        return "grades_sheet";
    }

    @PostMapping("/admin/revert-class")
    public String revertClass(@RequestParam int scheduleId) {
        service.revertClassToDraft(scheduleId);
        return "redirect:/admin/classes";
    }
}