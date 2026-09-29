package com.nicodim.ocapp;

import com.nicodim.ocapp.config.ConverterProperties;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(ConverterProperties.class)
public class OcappApplication {
    public static void main(String[] args) {
        SpringApplication.run(OcappApplication.class, normalizeArguments(args));
    }

    static String[] normalizeArguments(String[] args) {
        List<String> normalized = new ArrayList<>();
        Arrays.stream(args).forEach(argument -> {
            if (argument.startsWith("--config=")) {
                String value = argument.substring("--config=".length());
                if (value.isBlank()) throw new IllegalArgumentException("--config requires a file path");
                try {
                    String location = Path.of(value).toAbsolutePath().normalize().toUri().toASCIIString();
                    normalized.add("--spring.config.additional-location=" + location);
                } catch (InvalidPathException ex) {
                    throw new IllegalArgumentException("--config contains an invalid file path", ex);
                }
            } else {
                normalized.add(argument);
            }
        });
        return normalized.toArray(String[]::new);
    }
}
