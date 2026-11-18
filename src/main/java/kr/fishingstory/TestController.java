package kr.fishingstory;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

// Local connectivity check only; production does not register it.
@Profile("!prod")
@RestController
public class TestController {

    @GetMapping("/test")
    public String test() {
        return "Fishing Story API";
    }
}
