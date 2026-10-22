package it.projectsrl.standpos;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

// Users authenticate through LoginService and PinLoginService; no in-memory default user.
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
public class StandposApplication {

    public static void main(String[] args) {
        SpringApplication.run(StandposApplication.class, args);
    }
}
