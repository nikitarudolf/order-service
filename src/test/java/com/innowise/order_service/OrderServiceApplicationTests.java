package com.innowise.order_service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import io.jsonwebtoken.Jwts;
import java.util.Base64;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class OrderServiceApplicationTests {

	@DynamicPropertySource
	static void jwtSecret(DynamicPropertyRegistry registry) {
		String secret = Base64.getEncoder().encodeToString(Jwts.SIG.HS256.key().build().getEncoded());
		registry.add("jwt.secret", () -> secret);
	}

	@Test
	void contextLoads() {
	}

}
