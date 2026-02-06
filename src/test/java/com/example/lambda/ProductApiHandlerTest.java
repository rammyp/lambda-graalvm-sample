package com.example.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.example.lambda.handler.ProductApiHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ProductApiHandlerTest {

    private ProductApiHandler handler;
    private Context mockContext;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        handler = new ProductApiHandler();
        mapper = new ObjectMapper();

        // Simple mock context
        mockContext = new Context() {
            @Override public String getAwsRequestId() { return "test-request-id"; }
            @Override public String getLogGroupName() { return "/aws/lambda/test"; }
            @Override public String getLogStreamName() { return "test-stream"; }
            @Override public String getFunctionName() { return "test-function"; }
            @Override public String getFunctionVersion() { return "$LATEST"; }
            @Override public String getInvokedFunctionArn() { return "arn:aws:lambda:us-east-1:123:function:test"; }
            @Override public com.amazonaws.services.lambda.runtime.CognitoIdentity getIdentity() { return null; }
            @Override public com.amazonaws.services.lambda.runtime.ClientContext getClientContext() { return null; }
            @Override public int getRemainingTimeInMillis() { return 30000; }
            @Override public int getMemoryLimitInMB() { return 256; }
            @Override public LambdaLogger getLogger() {
                return new LambdaLogger() {
                    @Override public void log(String message) { System.out.println(message); }
                    @Override public void log(byte[] message) { System.out.println(new String(message)); }
                };
            }
        };
    }

    @Test
    void testGetAllProducts() throws Exception {
        var event = new APIGatewayProxyRequestEvent();
        event.setHttpMethod("GET");
        event.setPath("/products");

        APIGatewayProxyResponseEvent response = handler.handleRequest(event, mockContext);

        assertEquals(200, response.getStatusCode());

        JsonNode body = mapper.readTree(response.getBody());
        assertTrue(body.get("success").asBoolean());
        assertTrue(body.get("data").isArray());
        assertTrue(body.get("data").size() > 0);
    }

    @Test
    void testGetProductById() throws Exception {
        var event = new APIGatewayProxyRequestEvent();
        event.setHttpMethod("GET");
        event.setPath("/products/prod-001");

        APIGatewayProxyResponseEvent response = handler.handleRequest(event, mockContext);

        assertEquals(200, response.getStatusCode());

        JsonNode body = mapper.readTree(response.getBody());
        assertTrue(body.get("success").asBoolean());
        assertEquals("prod-001", body.get("data").get("id").asText());
    }

    @Test
    void testGetProductNotFound() throws Exception {
        var event = new APIGatewayProxyRequestEvent();
        event.setHttpMethod("GET");
        event.setPath("/products/nonexistent");

        APIGatewayProxyResponseEvent response = handler.handleRequest(event, mockContext);

        assertEquals(404, response.getStatusCode());
    }

    @Test
    void testCreateProduct() throws Exception {
        var event = new APIGatewayProxyRequestEvent();
        event.setHttpMethod("POST");
        event.setPath("/products");
        event.setBody("""
                {
                    "name": "Test Product",
                    "description": "A test product",
                    "price": 19.99,
                    "category": "Testing"
                }
                """);

        APIGatewayProxyResponseEvent response = handler.handleRequest(event, mockContext);

        assertEquals(201, response.getStatusCode());

        JsonNode body = mapper.readTree(response.getBody());
        assertTrue(body.get("success").asBoolean());
        assertEquals("Test Product", body.get("data").get("name").asText());
        assertNotNull(body.get("data").get("id").asText());
    }

    @Test
    void testDeleteProduct() throws Exception {
        var event = new APIGatewayProxyRequestEvent();
        event.setHttpMethod("DELETE");
        event.setPath("/products/prod-001");

        APIGatewayProxyResponseEvent response = handler.handleRequest(event, mockContext);

        assertEquals(200, response.getStatusCode());

        // Verify it's actually gone
        var getEvent = new APIGatewayProxyRequestEvent();
        getEvent.setHttpMethod("GET");
        getEvent.setPath("/products/prod-001");
        APIGatewayProxyResponseEvent getResponse = handler.handleRequest(getEvent, mockContext);
        assertEquals(404, getResponse.getStatusCode());
    }

    @Test
    void testSearchByCategory() throws Exception {
        var event = new APIGatewayProxyRequestEvent();
        event.setHttpMethod("GET");
        event.setPath("/products");
        event.setQueryStringParameters(Map.of("category", "Electronics"));

        APIGatewayProxyResponseEvent response = handler.handleRequest(event, mockContext);

        assertEquals(200, response.getStatusCode());

        JsonNode body = mapper.readTree(response.getBody());
        assertTrue(body.get("data").size() >= 2); // We seeded 2 electronics products
    }

    @Test
    void testHealthCheck() throws Exception {
        var event = new APIGatewayProxyRequestEvent();
        event.setHttpMethod("GET");
        event.setPath("/health");

        APIGatewayProxyResponseEvent response = handler.handleRequest(event, mockContext);

        assertEquals(200, response.getStatusCode());

        JsonNode body = mapper.readTree(response.getBody());
        assertEquals("healthy", body.get("data").get("status").asText());
    }

    @Test
    void testMethodNotAllowed() throws Exception {
        var event = new APIGatewayProxyRequestEvent();
        event.setHttpMethod("PATCH");
        event.setPath("/products");

        APIGatewayProxyResponseEvent response = handler.handleRequest(event, mockContext);

        assertEquals(405, response.getStatusCode());
    }
}
