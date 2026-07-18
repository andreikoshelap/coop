package com.gatto.funds;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class FundsApplication {
    public static void main(String[] args) {
        SpringApplication.run(FundsApplication.class, args);
    }
}
