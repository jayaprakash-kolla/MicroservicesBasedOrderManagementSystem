package com.jayaprakash.oms.discovery;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.netflix.eureka.server.EnableEurekaServer;

/**
 * The service registry.
 *
 * <p>Every business service registers itself here at startup and renews its
 * lease periodically. The gateway then routes to logical names such as
 * {@code lb://ORDER-SERVICE} instead of hard-coded host:port pairs, which is
 * what lets you run three copies of order-service without editing any config.
 *
 * <p>Start this first: the other services will retry registration, but the
 * gateway cannot resolve a route until at least one instance has registered.
 */
@SpringBootApplication
@EnableEurekaServer
public class DiscoveryServerApplication {

    /**
     * Boots the Eureka server on port 8761.
     *
     * @param args standard Spring Boot command line arguments
     */
    public static void main(String[] args) {
        SpringApplication.run(DiscoveryServerApplication.class, args);
    }
}
