package pulse_api.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pulse_api.dto.ProspectDtos.ImportResult;
import pulse_api.dto.ProspectDtos.ProspectDto;
import pulse_api.dto.ProspectDtos.ProspectUpsert;
import pulse_api.entity.Enums;
import pulse_api.entity.Prospect;
import pulse_api.exception.ResourceNotFoundException;
import pulse_api.repository.ProspectRepository;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Contract §2.5 — the Blaauwberg pipeline. */
@Service
@RequiredArgsConstructor
public class ProspectService {

    private static final DateTimeFormatter NOTE_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final ProspectRepository prospects;
    private final ProspectCsvParser csv;
    private final RangeResolver ranges;

    public List<ProspectDto> list(Enums.ProspectStatus status, boolean overdueOnly) {
        LocalDate today = ranges.today();
        List<Prospect> rows = status == null ? prospects.findAllByOrderByUpdatedAtDesc()
                : prospects.findByStatusOrderByUpdatedAtDesc(status);
        return rows.stream().map(p -> ProspectDto.from(p, today)).filter(p -> !overdueOnly || p.overdue()).toList();
    }

    @Transactional
    public ProspectDto create(ProspectUpsert body) {
        Prospect p = new Prospect();
        apply(p, body);
        return dto(prospects.save(p));
    }

    @Transactional
    public ProspectDto update(UUID id, ProspectUpsert body) {
        Prospect p = require(id);
        apply(p, body);
        return dto(prospects.save(p));
    }

    @Transactional
    public void delete(UUID id) {
        prospects.delete(require(id));
    }

    @Transactional
    public ProspectDto setStatus(UUID id, Enums.ProspectStatus status) {
        Prospect p = require(id);
        p.setStatus(status);
        return dto(prospects.save(p));
    }

    @Transactional
    public ProspectDto setNextAction(UUID id, String nextAction, LocalDate nextActionDate) {
        Prospect p = require(id);
        p.setNextAction(blankToNull(nextAction));
        p.setNextActionDate(nextActionDate);
        return dto(prospects.save(p));
    }

    @Transactional
    public ProspectDto addNote(UUID id, String note) {
        Prospect p = require(id);
        String line = "[" + ranges.now().format(NOTE_STAMP) + "] " + note.trim();
        p.setNotes(p.getNotes() == null || p.getNotes().isBlank() ? line : p.getNotes() + "\n" + line);
        return dto(prospects.save(p));
    }

    /** Dedupe by name + phone (case-insensitive name): existing rows are skipped, not overwritten. */
    @Transactional
    public ImportResult importCsv(String content) {
        ProspectCsvParser.Parsed parsed = csv.parse(content);
        List<String> errors = new ArrayList<>(parsed.errors());
        int imported = 0;
        int skipped = 0;
        for (ProspectUpsert row : parsed.rows()) {
            boolean exists = row.phone() == null
                    ? prospects.findFirstByNameIgnoreCaseAndPhoneIsNull(row.name()).isPresent()
                    : prospects.findFirstByNameIgnoreCaseAndPhone(row.name(), row.phone()).isPresent();
            if (exists) {
                skipped++;
                continue;
            }
            Prospect p = new Prospect();
            apply(p, row);
            prospects.save(p);
            imported++;
        }
        return new ImportResult(imported, skipped + parsed.errors().size(), errors);
    }

    private Prospect require(UUID id) {
        return prospects.findById(id).orElseThrow(() -> new ResourceNotFoundException("Prospect not found"));
    }

    private static void apply(Prospect p, ProspectUpsert b) {
        p.setName(b.name().trim());
        p.setBusiness(blankToNull(b.business()));
        p.setPhone(blankToNull(b.phone()));
        p.setArea(blankToNull(b.area()));
        p.setHasWebsite(b.hasWebsite());
        if (b.status() != null) {
            p.setStatus(b.status());
        }
        p.setNextAction(blankToNull(b.nextAction()));
        p.setNextActionDate(b.nextActionDate());
        p.setNotes(blankToNull(b.notes()));
    }

    private ProspectDto dto(Prospect p) {
        return ProspectDto.from(p, ranges.today());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
