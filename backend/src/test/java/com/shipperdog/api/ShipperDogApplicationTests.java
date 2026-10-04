package com.shipperdog.api;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** Boots the full context against a real PostgreSQL to prove the scaffold wires up. */
@SpringBootTest(properties = "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef")
@Testcontainers
class ShipperDogApplicationTests {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

    @Test
    void contextLoads() {}
}
