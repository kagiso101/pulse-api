package pulse_api.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import pulse_api.dto.DailySummaryDto;
import pulse_api.repository.DailySummaryRepository;

import java.util.List;

/** Contract §2.8 reads; the 07:00 build/send lives in {@code jobs.DailySummaryService}. */
@Service
@RequiredArgsConstructor
public class SummaryService {

    private final DailySummaryRepository summaries;

    public DailySummaryDto latest() {
        return summaries.findFirstByOrderBySummaryDateDesc().map(DailySummaryDto::from).orElse(null);
    }

    public List<DailySummaryDto> list(int limit) {
        return summaries.findAllByOrderBySummaryDateDesc(PageRequest.of(0, Math.max(1, Math.min(limit, 90)))).stream()
                .map(DailySummaryDto::from).toList();
    }
}
