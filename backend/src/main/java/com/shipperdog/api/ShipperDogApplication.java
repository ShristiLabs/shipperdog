package com.shipperdog.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point of the ShipperDog modular monolith.
 *
 * <p>Each bounded context lives in its own package below this one (identity,
 * organization, tenant, ...). Module boundaries are enforced by ArchUnit
 * tests; see {@code com.shipperdog.api.architecture.ModuleBoundaryArchTest}.
 */
@SpringBootApplication
public class ShipperDogApplication {

    public static void main(String[] args) {
        SpringApplication.run(ShipperDogApplication.class, args);
    }
}
