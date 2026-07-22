package com.aimall.server;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@MapperScan("com.aimall.server.mapper")
@EnableScheduling
public class AimallServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(AimallServerApplication.class, args);
    }
}
