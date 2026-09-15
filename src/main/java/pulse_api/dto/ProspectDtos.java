package pulse_api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import pulse_api.entity.Enums;
import pulse_api.entity.Prospect;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Contract §2.5. */
public final class ProspectDtos {

    private ProspectDtos() {}

    public record ProspectDto(UUID id, String name, String business, String phone, String area, Boolean hasWebsite,
                              Enums.ProspectStatus status, String nextAction, LocalDate nextActionDate, String notes,
                              Instant updatedAt, boolean overdue) {
        public static ProspectDto from(Prospect p, LocalDate today) {
            boolean overdue = p.getNextActionDate() != null && p.getNextActionDate().isBefore(today)
                    && p.getStatus() != Enums.ProspectStatus.tenant && p.getStatus() != Enums.ProspectStatus.declined;
            return new ProspectDto(p.getId(), p.getName(), p.getBusiness(), p.getPhone(), p.getArea(),
                    p.getHasWebsite(), p.getStatus(), p.getNextAction(), p.getNextActionDate(), p.getNotes(),
                    p.getUpdatedAt(), overdue);
        }
    }

    public record ProspectUpsert(@NotBlank @Size(max = 200) String name, @Size(max = 200) String business,
                                 @Size(max = 40) String phone, @Size(max = 120) String area, Boolean hasWebsite,
                                 Enums.ProspectStatus status, @Size(max = 500) String nextAction,
                                 LocalDate nextActionDate, @Size(max = 10000) String notes) {}

    public record StatusPatch(@NotNull Enums.ProspectStatus status) {}

    public record NextActionPatch(@Size(max = 500) String nextAction, LocalDate nextActionDate) {}

    public record NoteRequest(@NotBlank @Size(max = 2000) String note) {}

    public record ImportResult(int imported, int skipped, List<String> errors) {}
}
