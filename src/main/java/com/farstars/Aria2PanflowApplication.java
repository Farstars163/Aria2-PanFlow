package com.farstars;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class Aria2PanflowApplication {

    public static void main(String[] args) {
        SpringApplication.run(Aria2PanflowApplication.class, args);
    }

}
