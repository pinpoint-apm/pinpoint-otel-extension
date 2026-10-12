package bootsample;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestTemplate;

@SpringBootApplication
public class BootSampleApplication {

    public static void main(String[] args) {
        SpringApplication.run(BootSampleApplication.class, args);
    }

    /** A RestTemplate bean built from RestTemplateBuilder is instrumented by the starter (CLIENT span + header injection). */
    @Bean
    RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder.build();
    }

    /** For checking the setup: prints the sampler description once at startup. */
    @Bean
    CommandLineRunner printSampler(OpenTelemetry openTelemetry) {
        return args -> {
            if (openTelemetry instanceof OpenTelemetrySdk sdk) {
                System.out.println("SAMPLER " + sdk.getSdkTracerProvider().getSampler().getDescription());
            }
        };
    }
}
