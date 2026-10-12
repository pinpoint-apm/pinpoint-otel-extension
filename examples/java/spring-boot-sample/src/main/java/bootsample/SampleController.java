package bootsample;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

@RestController
public class SampleController {
    private final RestTemplate restTemplate;

    public SampleController(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /** The callee (B). Echoes the incoming tracestate so it can be checked by eye. */
    @GetMapping("/ping")
    public String ping(@RequestHeader(value = "tracestate", required = false) String tracestate) {
        return "pong tracestate=" + tracestate + "\n";
    }

    /** The caller (A). Calls B through the instrumented RestTemplate. */
    @GetMapping("/call")
    public String call(@RequestParam String target) {
        return "called " + target + " -> " + restTemplate.getForObject(target, String.class);
    }
}
