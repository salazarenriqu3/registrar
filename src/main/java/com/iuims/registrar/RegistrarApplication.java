package com.iuims.registrar;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootApplication
public class RegistrarApplication {

    @Autowired private JdbcTemplate db;

    public static void main(String[] args) {
        SpringApplication.run(RegistrarApplication.class, args);
    }

    @Bean
    public CommandLineRunner run() {
        return args -> {
            try {
                // Ensure passwords match what Java expects
                String validHash = org.mindrot.jbcrypt.BCrypt.hashpw("1234", org.mindrot.jbcrypt.BCrypt.gensalt());
                db.update("UPDATE sys_users SET password = ? WHERE username IN ('prof', 'admin')", validHash);
                System.out.println(">>> SYSTEM READY: Passwords for 'admin' and 'prof' synced to 1234.");
            } catch (Exception e) {}
        };
    }
}