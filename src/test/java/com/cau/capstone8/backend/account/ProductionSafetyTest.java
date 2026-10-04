package com.cau.capstone8.backend.account;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;

class ProductionSafetyTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(AuthConfiguration.class);

    @Test void productionCannotStartWithStubOrDemoAuthentication() {
        context.withPropertyValues("spring.profiles.active=production", "ml.mode=stub", "app.auth.mode=required")
                .run(c -> assertThat(c).hasFailed());
        context.withPropertyValues("spring.profiles.active=prod", "ml.mode=http", "app.auth.mode=demo",
                        "ml.base-url=http://ml:8000")
                .run(c -> assertThat(c).hasFailed());
    }
    @Test void productionCannotMixDemoSeedOrImplicitMlAddress() {
        context.withPropertyValues("spring.profiles.active=production,demo", "ml.mode=http", "app.auth.mode=required",
                        "ml.base-url=http://ml:8000")
                .run(c -> assertThat(c).hasFailed());
        context.withPropertyValues("spring.profiles.active=production", "ml.mode=http", "app.auth.mode=required")
                .run(c -> assertThat(c).hasFailed());
    }
    @Test void productionRejectsContainerRewritingTheRawPeerAddress() {
        context.withPropertyValues("spring.profiles.active=production", "ml.mode=http", "app.auth.mode=required",
                        "ml.base-url=http://ml:8000", "server.forward-headers-strategy=framework")
                .run(c -> assertThat(c).hasFailed());
    }
    @Test void explicitHttpAndRequiredAuthCanStartAndLocalDemoStillWorks() {
        context.withPropertyValues("spring.profiles.active=production", "ml.mode=http", "app.auth.mode=required",
                        "ml.base-url=http://ml:8000", "server.forward-headers-strategy=none")
                .run(c -> assertThat(c).hasNotFailed());
        context.withPropertyValues("spring.profiles.active=local", "ml.mode=stub", "app.auth.mode=demo")
                .run(c -> assertThat(c).hasNotFailed());
    }
    @Test void productionRejectsLegacyTomcatValveHeadersEvenWithNoneStrategy() {
        for (String property : new String[]{"server.tomcat.remoteip.remote-ip-header", "server.tomcat.remoteip.protocol-header"}) {
            context.withPropertyValues("spring.profiles.active=production", "ml.mode=http", "app.auth.mode=required",
                            "ml.base-url=http://ml:8000", "server.forward-headers-strategy=none", property+"=X-Forwarded-For")
                    .run(c -> assertThat(c).hasFailed());
        }
    }
}
