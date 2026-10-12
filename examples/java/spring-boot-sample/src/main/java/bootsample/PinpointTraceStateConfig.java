package bootsample;

import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * opentelemetry-spring-boot-starter applies every bean of type AutoConfigurationCustomizerProvider
 * when it builds the SDK. {@code otel.service.name} and {@code pinpoint.application-type} can come
 * from application.properties, a -D system property or an environment variable alike.
 */
@Configuration
public class PinpointTraceStateConfig {

    @Bean
    AutoConfigurationCustomizerProvider pinpointTraceState() {
        return customizer -> customizer.addSamplerCustomizer((sampler, config) ->
                new PinpointTraceStateSampler(sampler, null,
                        config.getString("otel.service.name"),
                        config.getInt("pinpoint.application-type")));     // null when unset
    }
}
