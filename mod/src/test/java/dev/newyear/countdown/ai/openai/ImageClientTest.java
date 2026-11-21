package dev.newyear.countdown.ai.openai;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ImageClientTest {
    private HttpServer server;
    private final AtomicInteger requests=new AtomicInteger();
    private JsonObject received;
    @AfterEach void stop() { if(server!=null)server.stop(0); }
    private OpenAiClient client(int status,String output) throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/images/generations",exchange->{
            requests.incrementAndGet();
            received=JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
            byte[] bytes=output.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(status,bytes.length);
            try(var stream=exchange.getResponseBody()){stream.write(bytes);}
        });
        server.start();
        return new OpenAiClient(()->new OpenAiClient.Settings("http://127.0.0.1:"+server.getAddress().getPort()+"/v1","sk-test-private-image-key",Duration.ofSeconds(2),3));
    }
    @Test void sendsImageRequestToImagesEndpointAndReturnsUsage() throws Exception {
        var client=client(200,"{data:[{b64_json:'example'}],usage:{input_tokens:100,output_tokens:20}}");
        var response=client.generateImage(TextureImages.request("gpt-image-2.5-flare","medium","star wand",16,"owner-hash"),null);
        assertEquals(1,requests.get());assertEquals("gpt-image-2.5-flare",received.get("model").getAsString());
        assertEquals("transparent",received.get("background").getAsString());
        assertEquals(20,response.getAsJsonObject("usage").get("output_tokens").getAsInt());
    }
    @Test void doesNotRetryPotentiallyBillableImageGeneration() throws Exception {
        var client=client(503,"{error:{message:'busy'}}");
        assertThrows(ApiException.class,()->client.generateImage(new JsonObject(),null));
        assertEquals(1,requests.get());
    }
    @Test void rejectedModelErrorsRedactTheKey() throws Exception {
        var client=client(400,"{error:{message:'invalid sk-test-private-image-key'}}");
        var error=assertThrows(ApiException.class,()->client.generateImage(new JsonObject(),null));
        assertFalse(error.getMessage().contains("sk-test-private-image-key"));
    }
    @Test void cancelledGenerationNeverStartsHttp() throws Exception {
        var client=client(200,"{}");var cancel=new OpenAiClient.CancelToken();cancel.cancel();
        assertEquals(ApiException.Kind.CANCELLED,assertThrows(ApiException.class,()->client.generateImage(new JsonObject(),cancel)).kind);
        assertEquals(0,requests.get());
    }
}
