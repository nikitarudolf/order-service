package com.innowise.order_service.client;

import com.innowise.order_service.dto.UserInfo;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

@Component
@RequiredArgsConstructor
public class UserServiceClient {

    private static final String USERS_BY_EMAIL_PATH = "/api/v1/users/by-email";

    private final RestClient userServiceRestClient;
    private final CircuitBreaker userServiceCircuitBreaker;

    public UserInfo getByEmail(String email, String token) {
        try {
            return userServiceCircuitBreaker.executeSupplier(() -> {
                UserInfo user = userServiceRestClient.get()
                        .uri(builder -> builder.path(USERS_BY_EMAIL_PATH)
                                .queryParam("email", "{email}").build(email))
                        .headers(headers -> headers.setBearerAuth(token))
                        .retrieve()
                        .body(UserInfo.class);
                if (user == null || user.id() == null) {
                    throw new RestClientException("User Service returned an incomplete profile");
                }
                return user;
            });
        } catch (HttpClientErrorException.NotFound ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User profile not found", ex);
        } catch (HttpClientErrorException.Forbidden ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied", ex);
        } catch (HttpClientErrorException.Unauthorized ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required", ex);
        } catch (RestClientException | CallNotPermittedException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "User Service is unavailable", ex);
        }
    }
}
