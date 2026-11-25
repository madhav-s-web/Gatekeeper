package in.coderarmy.gatekeeper.management.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Tells the dashboard where the Analytics Service lives (it has its own URL when deployed). */
@RestController
public class DashboardConfigController {

    private final String analyticsUrl;

    public DashboardConfigController(@Value("${gatekeeper.analytics-url:}") String analyticsUrl) {
        this.analyticsUrl = analyticsUrl.replaceAll("/+$", "").replace("\"", "");
    }

    @GetMapping(value = "/config.js", produces = "application/javascript")
    public ResponseEntity<String> config() {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/javascript"))
                .body("window.GATEKEEPER_ANALYTICS_URL = \"" + analyticsUrl + "\";\n");
    }
}
