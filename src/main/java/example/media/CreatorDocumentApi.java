package example.media;

import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
@RestController
public class CreatorDocumentApi {
    private final MediaSearchService search;
    public CreatorDocumentApi(MediaSearchService search) { this.search = search; }

    public static void main(String[] args) { SpringApplication.run(CreatorDocumentApi.class, args); }

    @PostMapping("/assets")
    public MediaSearchService.Receipt ingest(@RequestBody MediaSearchService.Asset asset) throws Exception {
        return search.ingest(asset);
    }

    @GetMapping("/answers")
    public MediaSearchService.Answer answer(@RequestParam("creatorId") String creatorId, @RequestParam("question") String question) throws Exception {
        return search.answer(creatorId, question);
    }

    @ExceptionHandler(MediaSearchService.ApiRejection.class)
    public ResponseEntity<Map<String, String>> rejected(MediaSearchService.ApiRejection error) {
        int status = error.status >= 400 && error.status < 500 ? error.status : 502;
        return ResponseEntity.status(status).body(Map.of("code", error.code, "message", error.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalid(IllegalArgumentException error) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", error.getMessage()));
    }
}
