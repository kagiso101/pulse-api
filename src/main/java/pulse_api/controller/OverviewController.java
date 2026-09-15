package pulse_api.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pulse_api.dto.OverviewDto;
import pulse_api.service.OverviewService;

/** Contract §2.2. */
@RestController
@RequiredArgsConstructor
public class OverviewController {

    private final OverviewService overview;

    @GetMapping("/api/overview")
    public OverviewDto overview(@RequestParam(required = false) String range) {
        return overview.overview(range);
    }
}
