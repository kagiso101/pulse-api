package pulse_api.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pulse_api.dto.DeployEventDto;
import pulse_api.service.DeployService;

import java.util.List;
import java.util.UUID;

/** Contract §2.7. */
@RestController
@RequiredArgsConstructor
public class DeployController {

    private final DeployService deploys;

    @GetMapping("/api/deploys")
    public List<DeployEventDto> list(@RequestParam(required = false) UUID projectId,
                                     @RequestParam(defaultValue = "50") int limit) {
        return deploys.list(projectId, limit);
    }
}
