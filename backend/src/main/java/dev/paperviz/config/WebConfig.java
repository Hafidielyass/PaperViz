package dev.paperviz.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Duration;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    /**
     * In compose the Angular container proxies /api through nginx, so no CORS is needed.
     * This only opens the door for `ng serve` on 4200 during frontend development.
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins("http://localhost:4200", "http://127.0.0.1:4200")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*");
    }

    /**
     * Shared HTTP client, with timeouts that are deliberately not infinite.
     *
     * Spring AI's Ollama client picks this up. Without a read timeout a single
     * GPU fault takes the pipeline down with it: a CUDA allocation failure left
     * one storyboard call hanging for 21 minutes before Ollama finally answered
     * 500, turning a 60-second section into a 23-minute one. Three minutes is
     * far longer than a healthy call — those run 10 to 20 seconds — so this only
     * ever fires on a genuinely stuck request, and the retry loop then does its
     * job instead of waiting forever.
     *
     * GROBID overrides this with a longer read timeout of its own, since parsing
     * a large PDF legitimately takes minutes.
     */
    @Bean
    RestClient.Builder restClientBuilder() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofMinutes(3));
        return RestClient.builder().requestFactory(factory);
    }
}
