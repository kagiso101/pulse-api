package pulse_api.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pulse_api.dto.CostDtos.CostReport;
import pulse_api.dto.CostDtos.ManualCostRequest;
import pulse_api.service.CostService;

/** Contract §2.6. */
@RestController
@RequestMapping("/api/costs")
@RequiredArgsConstructor
public class CostController {

    private final CostService costs;

    @GetMapping
    public CostReport report(@RequestParam(required = false) String month) {
        return costs.report(month);
    }

    @PutMapping("/manual")
    public CostReport manual(@Valid @RequestBody ManualCostRequest body) {
        return costs.upsertManual(body.provider(), body.month(), body.amountCents());
    }
}
