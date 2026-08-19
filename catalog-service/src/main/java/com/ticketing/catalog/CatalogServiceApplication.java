package com.ticketing.catalog;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication
@EnableCaching
public class CatalogServiceApplication {

	public static void main(String[] args) {
		System.out.println("JVM Timezone: " + java.util.TimeZone.getDefault().getID());
		SpringApplication.run(CatalogServiceApplication.class, args);
	}

}
