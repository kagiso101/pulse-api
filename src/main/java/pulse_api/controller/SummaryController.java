package pulse_api.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pulse_api.dto.DailySummaryDto;
import pulse_api.service.SummaryService;

import java.util.List;
import java.util.Optional;

/** Contract §2.8. */
@RestController
@RequestMapping("/api/summary")
@RequiredArgsConstructor
public class SummaryController {

    private final SummaryService summaries;

    /** Optional so an empty table serialises as a literal JSON {@code null} (contract: DailySummary | null). */
    @GetMapping("/latest")
    public Optional<DailySummaryDto> latest() {
        return Optional.ofNullable(summaries.latest());
    }

    @GetMapping
    public List<DailySummaryDto> list(@RequestParam(defaultValue = "14") int limit) {
        return summaries.list(limit);
    }
}
