package com.manthan.seat_reservation.config;

import java.time.Clock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.manthan.seat_reservation.auth.AuthInterceptor;
import com.manthan.seat_reservation.auth.AuthProperties;

@Configuration
@EnableConfigurationProperties(AuthProperties.class)
public class AuthConfig implements WebMvcConfigurer {

	private final AuthInterceptor authInterceptor;

	public AuthConfig(AuthInterceptor authInterceptor) {
		this.authInterceptor = authInterceptor;
	}

	@Bean
	static Clock clock() {
		return Clock.systemUTC();
	}

	/**
	 * Open paths: health, the UI page itself, and GET /users (the UI lists users before anyone has a token).
	 * POST /auth/token is skipped inside the interceptor, because a path pattern cannot match on the method.
	 */
	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(authInterceptor)
				.addPathPatterns("/**")
				.excludePathPatterns("/healthz", "/readyz", "/actuator/health", "/actuator/health/**", "/", "/index.html", "/users");
	}

}
