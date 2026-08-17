package com.example.codereview.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class OpenAiSseParserTest {

    @Test
    void parsesCrossChunkCrLfMultipleEventsAndTailUsage() {
        List<String> deltas = new ArrayList<>();
        OpenAiSseParser parser = new OpenAiSseParser(new ObjectMapper(), deltas::add);

        parser.feed("data: {\"choices\":[{\"delta\":{\"content\":\"你\"}}]}\r");
        parser.feed("\n\r\ndata: {\"choices\":[{\"delta\":{\"content\":\"好\"}}]}\n\n");
        parser.feed("data: {\"choices\":[],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2,\"total_tokens\":5}}\n\n");
        parser.feed("data: [DONE]");
        parser.finish();

        assertThat(deltas).containsExactly("你", "好");
        assertThat(parser.usage().totalTokens()).isEqualTo(5);
        assertThat(parser.done()).isTrue();
    }
}
