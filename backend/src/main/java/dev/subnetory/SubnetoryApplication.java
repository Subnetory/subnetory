package dev.subnetory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.data.web.config.EnableSpringDataWebSupport;

@SpringBootApplication
@EnableScheduling
@EnableSpringDataWebSupport(pageSerializationMode = EnableSpringDataWebSupport.PageSerializationMode.VIA_DTO)
public class SubnetoryApplication {

    public static void main(String[] args) {
        SpringApplication.run(SubnetoryApplication.class, args);
    }
}
