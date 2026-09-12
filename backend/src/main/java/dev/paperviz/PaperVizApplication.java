package dev.paperviz;

import dev.paperviz.config.PaperVizProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties(PaperVizProperties.class)
@EnableAsync
@EnableScheduling
public class PaperVizApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaperVizApplication.class, args);
    }
}
