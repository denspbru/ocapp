package com.nicodim.ocapp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.boot.SpringApplication;

class OcappApplicationTest {
    @Test void mainNormalizesArgumentsAndStartsSpringApplication() {
        try (MockedStatic<SpringApplication> springApplication = Mockito.mockStatic(SpringApplication.class)) {
            OcappApplication.main(new String[]{"--server.port=0"});
            springApplication.verify(() -> SpringApplication.run(eq(OcappApplication.class), any(String[].class)));
        }
    }

    @Test void applicationClassCanBeConstructedByFramework() {
        assertThat(new OcappApplication()).isNotNull();
    }

    @Test void translatesExternalConfigArgumentAsEscapedAbsoluteFileUri() {
        String expected = "--spring.config.additional-location=" + Path.of("target/config with space.properties").toAbsolutePath().normalize().toUri().toASCIIString();
        assertThat(OcappApplication.normalizeArguments(new String[]{"--config=target/config with space.properties", "--server.port=0"}))
            .containsExactly(expected, "--server.port=0");
    }

    @Test void preservesOrdinaryArguments() {
        assertThat(OcappApplication.normalizeArguments(new String[]{"--server.port=0"})).containsExactly("--server.port=0");
    }

    @Test void rejectsBlankOrInvalidConfigPath() {
        assertThatThrownBy(() -> OcappApplication.normalizeArguments(new String[]{"--config="})).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OcappApplication.normalizeArguments(new String[]{"--config=\0"})).isInstanceOf(IllegalArgumentException.class);
    }
}
