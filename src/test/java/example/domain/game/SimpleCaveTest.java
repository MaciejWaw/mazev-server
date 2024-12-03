package example.domain.game;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import example.domain.Response;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SimpleCaveTest {

    @Test
    void test() throws JsonProcessingException {
        ObjectMapper objectMapper = new ObjectMapper();
        final var str = objectMapper.writeValueAsString(new Response.StateCave(new SimpleCave(new boolean[10*10], 10, 10)));
        System.out.println(str);
    }
}