package it.projectsrl.standpos.tenant;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * {@code java -jar standpos-backend.jar --bootstrap} creates the first tenant from the environment and exits.
 * With {@code standpos.bootstrap.on-startup=true} (dev profile) it runs at startup and the server keeps running.
 */
@Component
class BootstrapRunner implements ApplicationRunner {

    private final BootstrapService bootstrap;
    private final BootstrapProperties properties;
    private final ConfigurableApplicationContext context;

    BootstrapRunner(BootstrapService bootstrap, BootstrapProperties properties, ConfigurableApplicationContext context) {
        this.bootstrap = bootstrap;
        this.properties = properties;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean command = args.containsOption("bootstrap");
        if (!command && !properties.onStartup()) {
            return;
        }
        bootstrap.bootstrap(properties);
        if (command) {
            System.exit(SpringApplication.exit(context, () -> 0));
        }
    }
}
