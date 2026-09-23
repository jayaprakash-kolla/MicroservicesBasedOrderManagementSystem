package com.jayaprakash.oms.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The single public entry point.
 *
 * <p>Clients only ever talk to port 8080. The gateway looks up the target
 * service in Eureka and forwards the request, which means the business services
 * can move, scale, or change ports without any client knowing.
 *
 * <p>In a production build this is also where authentication, request logging,
 * and global rate limiting would live, so those concerns are implemented once
 * rather than in all three services.
 *
 * <p>The routes themselves are declared in {@code application.yml}, since they
 * are configuration rather than logic.
 */
@SpringBootApplication
public class ApiGatewayApplication {

    /**
     * Boots the gateway on port 8080.
     *
     * @param args standard Spring Boot command line arguments
     */
    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}
