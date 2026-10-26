package it.projectsrl.standpos.realtime;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * One scheduler for {@code @Scheduled} tasks (agent presence sweep) and the STOMP broker's heart-beats. Named
 * {@code taskScheduler} so {@code @Scheduled} picks it among the WebSocket support's own schedulers.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class SchedulingConfig {

    @Bean
    ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("standpos-scheduler-");
        return scheduler;
    }
}
