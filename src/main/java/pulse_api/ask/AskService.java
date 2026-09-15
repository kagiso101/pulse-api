package pulse_api.ask;

import com.anthropic.core.http.StreamResponse;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.RawMessageStreamEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import pulse_api.dto.AskRequest;
import pulse_api.exception.ApiException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Read-only AI over the snapshot context (spec §5.9, contract §2.10). Streams Anthropic Messages
 * deltas as SSE events {@code delta}, then {@code done} (or a terminal {@code error}). The model
 * is told it can only describe the supplied data — it never triggers actions.
 */
@Slf4j
@Service
public class AskService {

    static final String SYSTEM_PROMPT = """
            You are the read-only analyst inside Pulse, ROGUETECHNOLOGIES' insights dashboard for Kagiso Hadebe.
            You are given a snapshot of the dashboard's data (projects, traffic, Bookvas platform numbers, alerts,
            prospects, costs) and one question. Rules:
            - Only describe, compare and summarise the supplied data. If something is not in the data, say so plainly.
            - You cannot take actions and must never claim or imply you took, scheduled or suggested one was taken
              (no restarts, redeploys, emails, status changes). If asked to act, explain that Pulse actions are done
              from the dashboard's Actions panel with a confirm step.
            - Answer briefly in plain text (short paragraphs or a few bullet lines). Write numbers as plain digits,
              money as rands like R1234.50, and times in South African time (Africa/Johannesburg).
            - Where a number reads "n/a" or "not available", it is a known Bookvas endpoint gap, not a zero.
            """;

    private static final long TIMEOUT_MS = 180_000;

    private final AnthropicClientFactory anthropic;
    private final AskContextBuilder context;
    private final ExecutorService executor;

    public AskService(AnthropicClientFactory anthropic, AskContextBuilder context, ExecutorService askExecutor) {
        this.anthropic = anthropic;
        this.context = context;
        this.executor = askExecutor;
    }

    public boolean configured() {
        return anthropic.configured();
    }

    public SseEmitter ask(AskRequest request) {
        if (!anthropic.configured()) {
            throw ApiException.notConfigured("Ask (ANTHROPIC_API_KEY)");
        }
        String contextBlock = context.build(request.projectSlug(), request.range());
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        AtomicBoolean open = new AtomicBoolean(true);
        emitter.onCompletion(() -> open.set(false));
        emitter.onTimeout(() -> open.set(false));
        emitter.onError(t -> open.set(false));
        executor.submit(() -> stream(emitter, open, contextBlock, request.question()));
        return emitter;
    }

    private void stream(SseEmitter emitter, AtomicBoolean open, String contextBlock, String question) {
        MessageCreateParams params = MessageCreateParams.builder()
                .model(anthropic.model())
                .maxTokens(anthropic.maxTokens())
                .system(SYSTEM_PROMPT)
                .addUserMessage("DATA:\n" + contextBlock + "\n\nQuestion: " + question.trim())
                .build();
        Long[] usage = {null, null};
        try (StreamResponse<RawMessageStreamEvent> s = anthropic.client().messages().createStreaming(params)) {
            s.stream().forEach(event -> {
                event.messageStart().ifPresent(start -> usage[0] = start.message().usage().inputTokens());
                event.messageDelta().ifPresent(delta -> {
                    usage[1] = delta.usage().outputTokens();
                    delta.usage().inputTokens().ifPresent(in -> usage[0] = in);
                });
                event.contentBlockDelta().flatMap(d -> d.delta().text()).ifPresent(t -> send(emitter, open, "delta",
                        Map.of("text", t.text())));
            });
            Map<String, Object> done = new LinkedHashMap<>();
            done.put("model", anthropic.model());
            done.put("inputTokens", usage[0]);
            done.put("outputTokens", usage[1]);
            send(emitter, open, "done", done);
            complete(emitter, open);
        } catch (AnthropicServiceException e) {
            log.warn("Ask upstream error {}: {}", e.statusCode(), e.getMessage());
            send(emitter, open, "error", Map.of("message", "The AI service returned an error (" + e.statusCode() + "). Please try again."));
            complete(emitter, open);
        } catch (Exception e) {
            log.warn("Ask failed: {}", e.toString());
            send(emitter, open, "error", Map.of("message", "Ask failed: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())));
            complete(emitter, open);
        }
    }

    private static void send(SseEmitter emitter, AtomicBoolean open, String name, Object data) {
        if (!open.get()) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name(name).data(data));
        } catch (Exception clientGone) {
            open.set(false);
        }
    }

    private static void complete(SseEmitter emitter, AtomicBoolean open) {
        if (open.compareAndSet(true, false)) {
            try {
                emitter.complete();
            } catch (Exception ignored) {
                // client already gone
            }
        }
    }
}
