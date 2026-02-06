package com.example.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.example.lambda.handler.ProductApiHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

/**
 * Custom Lambda runtime that talks directly to the Lambda Runtime API.
 * Deserializes events to Map then manually constructs APIGatewayProxyRequestEvent
 * to avoid GraalVM reflection issues with AWS event classes.
 */
public class CustomRuntime {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

    public static void main(String[] args) throws Exception {
        String runtimeApi = System.getenv("AWS_LAMBDA_RUNTIME_API");
        if (runtimeApi == null || runtimeApi.isEmpty()) {
            System.err.println("AWS_LAMBDA_RUNTIME_API not set");
            System.exit(1);
        }

        String baseUrl = "http://" + runtimeApi + "/2018-06-01/runtime";

        ProductApiHandler handler = new ProductApiHandler();
        System.out.println("Handler initialized, entering event loop...");

        while (true) {
            processNextEvent(baseUrl, handler);
        }
    }

    @SuppressWarnings("unchecked")
    private static void processNextEvent(String baseUrl, ProductApiHandler handler) {
        String requestId = null;
        try {
            // 1. Get next event
            HttpRequest nextRequest = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/invocation/next"))
                    .GET()
                    .build();

            HttpResponse<String> nextResponse = HTTP_CLIENT.send(nextRequest, HttpResponse.BodyHandlers.ofString());

            requestId = nextResponse.headers()
                    .firstValue("Lambda-Runtime-Aws-Request-Id")
                    .orElse("unknown");

            String deadlineMs = nextResponse.headers()
                    .firstValue("Lambda-Runtime-Deadline-Ms")
                    .orElse("0");

            String functionArn = nextResponse.headers()
                    .firstValue("Lambda-Runtime-Invoked-Function-Arn")
                    .orElse("");

            // 2. Deserialize to Map (avoids all GraalVM reflection issues)
            Map<String, Object> eventMap = MAPPER.readValue(nextResponse.body(), Map.class);

            // 3. Manually build APIGatewayProxyRequestEvent from Map
            APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent();
            event.setHttpMethod((String) eventMap.get("httpMethod"));
            event.setPath((String) eventMap.get("path"));
            event.setBody((String) eventMap.get("body"));
            event.setResource((String) eventMap.get("resource"));
            event.setIsBase64Encoded((Boolean) eventMap.get("isBase64Encoded"));

            if (eventMap.get("headers") instanceof Map) {
                event.setHeaders((Map<String, String>) eventMap.get("headers"));
            }
            if (eventMap.get("queryStringParameters") instanceof Map) {
                event.setQueryStringParameters((Map<String, String>) eventMap.get("queryStringParameters"));
            }
            if (eventMap.get("pathParameters") instanceof Map) {
                event.setPathParameters((Map<String, String>) eventMap.get("pathParameters"));
            }
            if (eventMap.get("stageVariables") instanceof Map) {
                event.setStageVariables((Map<String, String>) eventMap.get("stageVariables"));
            }
            if (eventMap.get("multiValueHeaders") instanceof Map) {
                event.setMultiValueHeaders((Map) eventMap.get("multiValueHeaders"));
            }
            if (eventMap.get("multiValueQueryStringParameters") instanceof Map) {
                event.setMultiValueQueryStringParameters((Map) eventMap.get("multiValueQueryStringParameters"));
            }

            // 4. Create context
            Context context = createContext(requestId, deadlineMs, functionArn);

            // 5. Invoke handler
            APIGatewayProxyResponseEvent response = handler.handleRequest(event, context);

            // 6. Post response (serialize as Map to avoid reflection issues)
            Map<String, Object> responseMap = new java.util.HashMap<>();
            responseMap.put("statusCode", response.getStatusCode());
            responseMap.put("headers", response.getHeaders());
            responseMap.put("body", response.getBody());
            if (response.getIsBase64Encoded() != null) {
                responseMap.put("isBase64Encoded", response.getIsBase64Encoded());
            }
            if (response.getMultiValueHeaders() != null) {
                responseMap.put("multiValueHeaders", response.getMultiValueHeaders());
            }

            String responseBody = MAPPER.writeValueAsString(responseMap);
            HttpRequest responseRequest = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/invocation/" + requestId + "/response"))
                    .POST(HttpRequest.BodyPublishers.ofString(responseBody))
                    .header("Content-Type", "application/json")
                    .build();

            HTTP_CLIENT.send(responseRequest, HttpResponse.BodyHandlers.ofString());

        } catch (Exception e) {
            System.err.println("Error processing event: " + e.getMessage());
            e.printStackTrace();

            if (requestId != null) {
                try {
                    String errorBody = "{\"errorMessage\":\"" +
                            (e.getMessage() != null ? e.getMessage().replace("\"", "'") : "Unknown error") +
                            "\",\"errorType\":\"" + e.getClass().getSimpleName() + "\"}";
                    HttpRequest errorRequest = HttpRequest.newBuilder()
                            .uri(URI.create(baseUrl + "/invocation/" + requestId + "/error"))
                            .POST(HttpRequest.BodyPublishers.ofString(errorBody))
                            .header("Content-Type", "application/json")
                            .build();
                    HTTP_CLIENT.send(errorRequest, HttpResponse.BodyHandlers.ofString());
                } catch (Exception ex) {
                    System.err.println("Failed to report error: " + ex.getMessage());
                }
            }
        }
    }

    private static Context createContext(String requestId, String deadlineMs, String functionArn) {
        return new Context() {
            @Override public String getAwsRequestId() { return requestId; }
            @Override public String getLogGroupName() { return System.getenv("AWS_LAMBDA_LOG_GROUP_NAME"); }
            @Override public String getLogStreamName() { return System.getenv("AWS_LAMBDA_LOG_STREAM_NAME"); }
            @Override public String getFunctionName() { return System.getenv("AWS_LAMBDA_FUNCTION_NAME"); }
            @Override public String getFunctionVersion() { return System.getenv("AWS_LAMBDA_FUNCTION_VERSION"); }
            @Override public String getInvokedFunctionArn() { return functionArn; }
            @Override public com.amazonaws.services.lambda.runtime.CognitoIdentity getIdentity() { return null; }
            @Override public com.amazonaws.services.lambda.runtime.ClientContext getClientContext() { return null; }
            @Override public int getRemainingTimeInMillis() {
                try { return (int)(Long.parseLong(deadlineMs) - System.currentTimeMillis()); }
                catch (Exception e) { return 30000; }
            }
            @Override public int getMemoryLimitInMB() {
                try { return Integer.parseInt(System.getenv("AWS_LAMBDA_FUNCTION_MEMORY_SIZE")); }
                catch (Exception e) { return 256; }
            }
            @Override public LambdaLogger getLogger() {
                return new LambdaLogger() {
                    @Override public void log(String message) { System.out.println(message); }
                    @Override public void log(byte[] message) { System.out.println(new String(message)); }
                };
            }
        };
    }
}
