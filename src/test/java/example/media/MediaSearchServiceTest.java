package example.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.List;
import org.junit.jupiter.api.Test;

class MediaSearchServiceTest {
    @Test void indexedPassagesCoverEveryCharacterWithoutDuplicates() {
        List<String> chunks = MediaSearchService.split("  paid   4800  cleared ", 8);
        assertEquals(List.of("paid 480", "0 cleare", "d"), chunks);
        assertEquals("paid 4800 cleared", String.join("", chunks));
    }
}
