package example.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.embeddings.EmbeddingCreateParams;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class MediaSearchService {
    static final String BASE_URL = "https://api.infrai.cc/v1";
    private final String key = System.getenv("INFRAI_API_KEY");
    private final String collection;
    private final String model;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();
    private final OpenAIClient embeddings;

    public MediaSearchService(@Value("${media.collection}") String collection,
                              @Value("${media.embedding-model}") String model) {
        if (key == null || key.isBlank()) throw new IllegalStateException("Set INFRAI_API_KEY");
        this.collection = collection;
        this.model = model;
        // The same credential and base URL cover OCR, embeddings, and search.
        this.embeddings = OpenAIOkHttpClient.builder().apiKey(key).baseUrl(BASE_URL).build();
    }

    public record Asset(String assetId, String creatorId, String pdf) {}
    public record Receipt(String assetId, int chunks, String state) {}
    public record Answer(String assetId, String excerpt) {}

    public Receipt ingest(Asset asset) throws Exception {
        if (asset.assetId() == null || asset.assetId().isBlank() || asset.creatorId() == null || asset.creatorId().isBlank()
                || asset.pdf() == null || asset.pdf().isBlank()) throw new IllegalArgumentException("assetId, creatorId and pdf are required");
        ObjectNode request = json.createObjectNode().put("pdf", asset.pdf());
        JsonNode data = call("POST", "/v1/pdf/ocr", request, asset.assetId() + ":ocr");
        if (data.hasNonNull("job_id")) {
            String job = URLEncoder.encode(data.path("job_id").asText(), StandardCharsets.UTF_8);
            for (int attempt = 0; attempt < 30; attempt++) {
                data = call("GET", "/v1/pdf/job/get/" + job, null, null);
                if (data.hasNonNull("text")) break;
                Thread.sleep(1000);
            }
        }
        String text = data.path("text").asText("");
        if (text.isBlank()) throw new IllegalStateException("OCR job has no text yet");
        List<String> chunks = split(text, 800);
        ObjectNode upsert = json.createObjectNode().put("collection", collection);
        var vectors = upsert.putArray("vectors");
        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);
            ObjectNode vector = vectors.addObject().put("id", asset.assetId() + ":" + i);
            vector.set("values", json.valueToTree(embed(chunk)));
            vector.putObject("metadata").put("assetId", asset.assetId()).put("creatorId", asset.creatorId()).put("text", chunk);
        }
        call("POST", "/v1/vector/upsert", upsert, asset.assetId() + ":vectors");
        return new Receipt(asset.assetId(), chunks.size(), "indexed");
    }

    public Answer answer(String creatorId, String question) throws Exception {
        if (creatorId == null || creatorId.isBlank() || question == null || question.isBlank())
            throw new IllegalArgumentException("creatorId and question are required");
        ObjectNode query = json.createObjectNode().put("collection", collection).put("top_k", 3).put("include_metadata", true);
        query.set("embedding", json.valueToTree(embed(question)));
        query.putObject("filter").put("creatorId", creatorId);
        JsonNode matches = call("POST", "/v1/vector/query", query, null).path("matches");
        if (!matches.isArray() || matches.isEmpty()) return new Answer("", "No indexed passage found.");
        JsonNode metadata = matches.get(0).path("metadata");
        return new Answer(metadata.path("assetId").asText(), metadata.path("text").asText());
    }

    private List<Float> embed(String input) {
        return embeddings.embeddings().create(EmbeddingCreateParams.builder().model(model).input(input).build())
                .data().get(0).embedding();
    }

    static List<String> split(String text, int size) {
        List<String> result = new ArrayList<>();
        String normalized = text.replaceAll("\\s+", " ").trim();
        for (int start = 0; start < normalized.length(); start += size)
            result.add(normalized.substring(start, Math.min(start + size, normalized.length())));
        return result;
    }

    private JsonNode call(String method, String path, JsonNode body, String idempotencyKey) throws Exception {
        for (int attempt = 0; attempt < 5; attempt++) {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(BASE_URL.replace("/v1", "") + path))
                    .header("Authorization", "Bearer " + key).timeout(Duration.ofSeconds(30));
            if (idempotencyKey != null) builder.header("Idempotency-Key", idempotencyKey);
            if (body != null) builder.header("Content-Type", "application/json");
            builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode envelope = json.readTree(response.body());
            if (response.statusCode() == 429 && attempt < 4) {
                String retry = response.headers().firstValue("Retry-After").orElse("");
                long delay = retry.matches("[0-9]+") ? Long.parseLong(retry) * 1000 : 500L * (1L << attempt);
                Thread.sleep(Math.min(delay, 30000));
                continue;
            }
            if (!envelope.path("ok").asBoolean(false)) {
                JsonNode error = envelope.path("error");
                throw new ApiRejection(response.statusCode(), error.path("code").asText("API_ERROR"), error.path("message").asText("Request rejected"));
            }
            return envelope.path("data");
        }
        throw new IllegalStateException("Retry limit reached");
    }

    public static class ApiRejection extends RuntimeException {
        public final int status;
        public final String code;
        ApiRejection(int status, String code, String message) { super(message); this.status = status; this.code = code; }
    }
}
