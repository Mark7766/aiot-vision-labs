package com.sandy.aiot.vision.collector;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class AiotVisionCollectorApplication {

	public static void main(String[] args) {
		SpringApplication.run(AiotVisionCollectorApplication.class, args);
	}

}
