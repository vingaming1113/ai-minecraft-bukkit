package dev.kilo.aibots.llm;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.NotNull;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Chat-completions client. Speaks the OpenAI wire format, which is supported by
 * OpenRouter, OpenAI, Groq, DeepSeek, Mistral, xAI, Together, Ollama, LM Studio,
 * vLLM and basically every other provider - pick a preset or set a custom base URL.
 */
public final class LLMService {

    private static final Map<String, String> PRESETS = Map.of(
            "openrouter", "https://openrouter.ai/api/v1",
            "openai", "https://api.openai.com/v1",
            "groq", "https://api.groq.com/openai/v1",
            "deepseek", "https://api.deepseek.com/v1",
            "mistral", "https://api.mistral.ai/v1",
            "xai", "https://api.x.ai/v1",
            "together", "https://api.together.xyz/v1",
            "ollama", "http://localhost:11434/v1",
            "lmstudio", "http://localhost:1234/v1"
    );

    public record Message(String role, String content) {
    }

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final Gson gson = new Gson();

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final double temperature;
    private final int maxTokens;
    private final int timeoutSeconds;

    public LLMService(ConfigurationSection cfg) {
        String provider = cfg.getString("provider", "openrouter").toLowerCase();
        String url = cfg.getString("base-url", "");
        if (url == null || url.isBlank()) url = PRESETS.getOrDefault(provider, PRESETS.get("openrouter"));
        this.baseUrl = url.replaceAll("/+$", "");
        this.apiKey = cfg.getString("api-key", "");
        this.model = cfg.getString("model", "openai/gpt-4o-mini");
        this.temperature = cfg.getDouble("temperature", 0.7);
        this.maxTokens = cfg.getInt("max-tokens", 400);
        this.timeoutSeconds = cfg.getInt("request-timeout-seconds", 30);
    }

    public CompletableFuture<String> chat(@NotNull List<Message> messages) {
        CompletableFuture<String> future = new CompletableFuture<>();

        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("temperature", temperature);
        body.addProperty("max_tokens", maxTokens);
        JsonArray arr = new JsonArray();
        for (Message m : messages) {
            JsonObject o = new JsonObject();
            o.addProperty("role", m.role());
            o.addProperty("content", m.content());
            arr.add(o);
        }
        body.add("messages", arr);

        HttpRequest.Builder rb = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)));
        if (apiKey != null && !apiKey.isBlank()) rb.header("Authorization", "Bearer " + apiKey);

        http.sendAsync(rb.build(), HttpResponse.BodyHandlers.ofString())
                .whenComplete((resp, err) -> {
                    if (err != null) {
                        future.completeExceptionally(err);
                        return;
                    }
                    if (resp.statusCode() / 100 != 2) {
                        future.completeExceptionally(new RuntimeException(
                                "AI request failed (" + resp.statusCode() + "): " + truncate(resp.body())));
                        return;
                    }
                    try {
                        JsonObject json = gson.fromJson(resp.body(), JsonObject.class);
                        future.complete(json.getAsJsonArray("choices").get(0).getAsJsonObject()
                                .getAsJsonObject("message").get("content").getAsString().trim());
                    } catch (RuntimeException e) {
                        future.completeExceptionally(new RuntimeException("Unexpected AI response: "
                                + truncate(resp.body())));
                    }
                });

        return future;
    }

    private static String truncate(String s) {
        return s == null ? "null" : (s.length() > 300 ? s.substring(0, 300) + "..." : s);
    }
}
