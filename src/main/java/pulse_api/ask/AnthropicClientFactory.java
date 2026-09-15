package pulse_api.ask;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Lazily builds the Anthropic client from ANTHROPIC_API_KEY; the key never leaves this class. */
@Component
public class AnthropicClientFactory {

    private final String apiKey;
    private final String model;
    private final long maxTokens;
    private volatile AnthropicClient client;

    public AnthropicClientFactory(@Value("${app.anthropic.api-key:}") String apiKey,
                                  @Value("${app.anthropic.model:claude-opus-5}") String model,
                                  @Value("${app.anthropic.max-tokens:4096}") long maxTokens) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model == null || model.isBlank() ? "claude-opus-5" : model.trim();
        this.maxTokens = maxTokens;
    }

    public boolean configured() {
        return !apiKey.isBlank();
    }

    public String model() {
        return model;
    }

    public long maxTokens() {
        return maxTokens;
    }

    public AnthropicClient client() {
        AnthropicClient c = client;
        if (c == null) {
            synchronized (this) {
                if (client == null) {
                    client = AnthropicOkHttpClient.builder()
                            .apiKey(apiKey)
                            .timeout(Duration.ofSeconds(120))
                            .build();
                }
                c = client;
            }
        }
        return c;
    }
}
