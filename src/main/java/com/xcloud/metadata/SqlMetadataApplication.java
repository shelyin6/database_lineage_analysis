package com.xcloud.metadata;

import com.xcloud.metadata.config.MetadataProperties;
import com.xcloud.metadata.inceptor.InceptorProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({MetadataProperties.class, InceptorProperties.class})
public class SqlMetadataApplication {
    public static void main(String[] args) {
        configureExternalConfig();
        SpringApplication.run(SqlMetadataApplication.class, args);
    }

    private static void configureExternalConfig() {
        if (System.getProperty("spring.config.additional-location") != null
                || System.getenv("SPRING_CONFIG_ADDITIONAL_LOCATION") != null) {
            return;
        }
        Path applicationDirectory = MetadataProperties.findApplicationDirectory();
        List<String> locations = new ArrayList<>();
        Path configDirectory = applicationDirectory.resolve("config");
        if (Files.isDirectory(configDirectory)) {
            locations.add(configDirectory.toUri().toString());
        }
        for (String file : List.of("application.yml", "application.yaml", "application.properties")) {
            Path configFile = applicationDirectory.resolve(file);
            if (Files.isRegularFile(configFile)) {
                locations.add(configFile.toUri().toString());
            }
        }
        if (!locations.isEmpty()) {
            System.setProperty("spring.config.additional-location", String.join(",", locations));
        }
    }
}
