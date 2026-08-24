package dev.kilo.aibots.llm;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
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
import java.util.concurrent.TimeUnit;

/**
 * Chat-completions client. Speaks the OpenAI wire format, which is supported by
 * OpenRouter, OpenAI, Groq, DeepSeek, Mistral, xAI, Together, Ollama, LM Studio,
 * vLLM and basically every other provider - pick a preset or set a custom base URL.
 * <p>
 * Deliberately minimal request body: model + temperature + messages only.
 * No max_tokens, no reasoning params - different models have different budgets
 * and efforts, so we send nothing the provider could reject. If a 400 does come
 * back, we retry once with temperature stripped too. Transient errors retry once,
 * and reasoning-only responses fall back to the model's reasoning text.
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
    private final int timeoutSeconds;

    public LLMService(ConfigurationSection cfg) {
        String url = cfg.getString("base-url", "");
        if (url == null || url.isBlank()) {
            url = PRESETS.getOrDefault(cfg.getString("provider", "openrouter").toLowerCase(), PRESETS.get("openrouter"));
        }
        this.baseUrl = url.replaceAll("/+$", "");
        this.apiKey = cfg.getString("api-key", "");
        this.model = cfg.getString("model", "openai/gpt-4o-mini");
        this.temperature = cfg.getDouble("temperature", 0.7);
        this.timeoutSeconds = cfg.getInt("request-timeout-seconds", 30);
    }

    public CompletableFuture<String> chat(@NotNull List<Message> messages) {
        return chat(messages, null);
    }

    /** modelOverride: per-bot model, falls back to the global one when null/blank. */
    public CompletableFuture<String> chat(@NotNull List<Message> messages, String modelOverride) {
        String mdl = modelOverride != null && !modelOverride.isBlank() ? modelOverride.trim() : model;
        CompletableFuture<String> future = new CompletableFuture<>();
        attempt(messages, mdl, true, 2, future);
        return future;
    }

    private HttpRequest buildRequest(List<Message> messages, String mdl, boolean includeTemperature) {
        JsonObject body = new JsonObject();
        body.addProperty("model", mdl);
        if (includeTemperature) body.addProperty("temperature", temperature);

        JsonArray arr = new JsonArray();
        for (Message m : messages) {
            JsonObject o = new JsonObject();
            o.addProperty("role", m.role());
            o.addProperty("content", m.content());
            arr.add(o);
        }
        body.add("messages", arr);

        return HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
                .header("Authorization", "Bearer " + (apiKey == null ? "" : apiKey))
                .build();
    }

    private void attempt(List<Message> messages, String mdl, boolean includeTemperature,
                         int triesLeft, CompletableFuture<String> future) {
        HttpRequest request = buildRequest(messages, mdl, includeTemperature);
        http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .orTimeout(timeoutSeconds + 5L, TimeUnit.SECONDS)
                .whenComplete((resp, err) -> {
                    if (err != null) {
                        retryOrFail(messages, mdl, includeTemperature, triesLeft, future,
                                new RuntimeException(err.getMessage()));
                        return;
                    }
                    if (resp.statusCode() / 100 != 2) {
                        String body = truncate(resp.body());

                        // strict providers reject temperature for some models -
                        // strip it and try again before giving up
                        if (resp.statusCode() == 400 && includeTemperature) {
                            Bukkit.getLogger().info("[AIBots] Provider rejected the request (400) - "
                                    + "retrying without temperature.");
                            attempt(messages, mdl, false, triesLeft, future);
                            return;
                        }

                        RuntimeException e = new RuntimeException(
                                "AI request failed (" + resp.statusCode() + "): " + body);
                        if (resp.statusCode() >= 500 || resp.statusCode() == 408 || resp.statusCode() == 429) {
                            retryOrFail(messages, mdl, includeTemperature, triesLeft, future, e);
                        } else {
                            future.completeExceptionally(e);
                        }
                        return;
                    }
                    try {
                        String content = extractContent(resp.body());
                        if (content == null || content.isBlank()) {
                            throw new IllegalStateException("empty response");
                        }
                        future.complete(content.trim());
                    } catch (RuntimeException e) {
                        retryOrFail(messages, mdl, includeTemperature, triesLeft, future,
                                new RuntimeException("Unexpected AI response: " + truncate(resp.body())));
                    }
                });
    }

    /** Pulls message.content; falls back to reasoning fields some models fill instead. */
    private String extractContent(String raw) {
        JsonObject json = gson.fromJson(raw, JsonObject.class);
        JsonArray choices = json.getAsJsonArray("choices");
        if (choices == null || choices.size() == 0) return null;
        JsonObject choice = choices.get(0).getAsJsonObject();
        JsonObject msg = choice.has("message") ? choice.getAsJsonObject("message") : null;
        if (msg == null) return null;
        if (msg.has("content") && !msg.get("content").isJsonNull()) {
            String c = msg.get("content").getAsString();
            if (c != null && !c.isBlank()) return c;
        }
        for (String key : new String[]{"reasoning_content", "reasoning"}) {
            if (msg.has(key) && !msg.get(key).isJsonNull()) {
                return msg.get(key).getAsString();
            }
        }
        return null;
    }

    private void retryOrFail(List<Message> messages, String mdl, boolean includeTemperature,
                             int triesLeft, CompletableFuture<String> future, RuntimeException error) {
        if (triesLeft > 1) {
            CompletableFuture.delayedExecutor(1500, TimeUnit.MILLISECONDS)
                    .execute(() -> attempt(messages, mdl, includeTemperature, triesLeft - 1, future));
        } else {
            future.completeExceptionally(error);
        }
    }

    private static String truncate(String s) {
        return s == null ? "null" : (s.length() > 300 ? s.substring(0, 300) + "..." : s);
    }
}
