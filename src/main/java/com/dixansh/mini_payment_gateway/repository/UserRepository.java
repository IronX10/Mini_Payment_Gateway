package com.dixansh.mini_payment_gateway.repository;

import com.dixansh.mini_payment_gateway.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    Optional<User> findByApiKeyHash(String apiKeyHash);
}
