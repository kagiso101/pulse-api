package pulse_api.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import pulse_api.dto.ProspectDtos.ImportResult;
import pulse_api.dto.ProspectDtos.NextActionPatch;
import pulse_api.dto.ProspectDtos.NoteRequest;
import pulse_api.dto.ProspectDtos.ProspectDto;
import pulse_api.dto.ProspectDtos.ProspectUpsert;
import pulse_api.dto.ProspectDtos.StatusPatch;
import pulse_api.entity.Enums;
import pulse_api.service.ProspectService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/** Contract §2.5. */
@RestController
@RequestMapping("/api/prospects")
@RequiredArgsConstructor
public class ProspectController {

    private final ProspectService prospects;

    @GetMapping
    public List<ProspectDto> list(@RequestParam(required = false) Enums.ProspectStatus status,
                                  @RequestParam(defaultValue = "false") boolean overdue) {
        return prospects.list(status, overdue);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProspectDto create(@Valid @RequestBody ProspectUpsert body) {
        return prospects.create(body);
    }

    @PutMapping("/{id}")
    public ProspectDto update(@PathVariable UUID id, @Valid @RequestBody ProspectUpsert body) {
        return prospects.update(id, body);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        prospects.delete(id);
    }

    @PatchMapping("/{id}/status")
    public ProspectDto status(@PathVariable UUID id, @Valid @RequestBody StatusPatch body) {
        return prospects.setStatus(id, body.status());
    }

    @PatchMapping("/{id}/next-action")
    public ProspectDto nextAction(@PathVariable UUID id, @Valid @RequestBody NextActionPatch body) {
        return prospects.setNextAction(id, body.nextAction(), body.nextActionDate());
    }

    @PostMapping("/{id}/notes")
    public ProspectDto note(@PathVariable UUID id, @Valid @RequestBody NoteRequest body) {
        return prospects.addNote(id, body.note());
    }

    @PostMapping(value = "/import", consumes = "multipart/form-data")
    public ImportResult importCsv(@RequestParam("file") MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Upload a CSV file in the 'file' field");
        }
        return prospects.importCsv(new String(file.getBytes(), StandardCharsets.UTF_8));
    }
}
