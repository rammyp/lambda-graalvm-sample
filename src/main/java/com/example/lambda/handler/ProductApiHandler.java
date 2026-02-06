package com.example.lambda.handler;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.example.lambda.model.Product;
import com.example.lambda.service.ProductService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.util.*;

/**
 * AWS Lambda handler for a simple Product REST API.
 * Uses Map-based JSON serialization to avoid GraalVM reflection issues.
 */
public class ProductApiHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    private static final Map<String, String> CORS_HEADERS = Map.of(
            "Content-Type", "application/json",
            "Access-Control-Allow-Origin", "*",
            "Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS",
            "Access-Control-Allow-Headers", "Content-Type, Authorization"
    );

    private final ProductService productService;

    public ProductApiHandler() {
        this.productService = new ProductService();
        System.out.println("ProductApiHandler initialized");
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent event, Context context) {
        context.getLogger().log("Received: " + event.getHttpMethod() + " " + event.getPath());
        long start = System.currentTimeMillis();

        try {
            var response = routeRequest(event, context);
            context.getLogger().log("Processed in " + (System.currentTimeMillis() - start) + "ms");
            return response;
        } catch (Exception e) {
            context.getLogger().log("Error: " + e.getMessage());
            return buildResponse(500, errorJson("Internal server error: " + e.getMessage()));
        }
    }

    private APIGatewayProxyResponseEvent routeRequest(APIGatewayProxyRequestEvent event, Context context) throws Exception {
        String method = event.getHttpMethod();
        String path = event.getPath() != null ? event.getPath() : "/";

        if ("OPTIONS".equalsIgnoreCase(method)) {
            return buildResponse(200, "\"ok\"");
        }

        return switch (method.toUpperCase()) {
            case "GET" -> handleGet(path, event);
            case "POST" -> handlePost(path, event);
            case "DELETE" -> handleDelete(path, event);
            default -> buildResponse(405, errorJson("Method not allowed: " + method));
        };
    }

    private APIGatewayProxyResponseEvent handleGet(String path, APIGatewayProxyRequestEvent event) throws Exception {
        if ("/products".equals(path)) {
            var queryParams = event.getQueryStringParameters();
            if (queryParams != null && queryParams.containsKey("category")) {
                var products = productService.searchByCategory(queryParams.get("category"));
                return buildResponse(200, okJson("Found " + products.size() + " products", productsToList(products)));
            }
            var products = productService.getAllProducts();
            return buildResponse(200, okJson(productsToList(products)));
        }

        if (path.matches("/products/.+")) {
            String id = path.substring("/products/".length());
            return productService.getProductById(id)
                    .map(p -> buildResponse(200, okJson(productToMap(p))))
                    .orElseGet(() -> buildResponse(404, errorJson("Product not found: " + id)));
        }

        if ("/health".equals(path)) {
            Map<String, Object> health = new LinkedHashMap<>();
            health.put("status", "healthy");
            health.put("runtime", "GraalVM Native Image");
            health.put("java", System.getProperty("java.version", "native"));
            health.put("timestamp", java.time.Instant.now().toString());
            return buildResponse(200, okJson("Service is healthy", health));
        }

        return buildResponse(404, errorJson("Not found: " + path));
    }

    private APIGatewayProxyResponseEvent handlePost(String path, APIGatewayProxyRequestEvent event) throws Exception {
        if ("/products".equals(path)) {
            if (event.getBody() == null || event.getBody().isBlank()) {
                return buildResponse(400, errorJson("Request body is required"));
            }
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> body = MAPPER.readValue(event.getBody(), Map.class);
                String name = (String) body.get("name");
                String description = (String) body.get("description");
                double price = body.get("price") instanceof Number n ? n.doubleValue() : 0;
                String category = (String) body.get("category");

                var product = Product.create(
                        "prod-" + UUID.randomUUID().toString().substring(0, 8),
                        name, description, price, category);
                var created = productService.createProduct(product);
                return buildResponse(201, okJson("Product created", productToMap(created)));
            } catch (IllegalArgumentException e) {
                return buildResponse(400, errorJson("Validation error: " + e.getMessage()));
            }
        }
        return buildResponse(404, errorJson("Not found: " + path));
    }

    private APIGatewayProxyResponseEvent handleDelete(String path, APIGatewayProxyRequestEvent event) throws Exception {
        if (path.matches("/products/.+")) {
            String id = path.substring("/products/".length());
            return productService.deleteProduct(id)
                    .map(p -> buildResponse(200, okJson("Product deleted", productToMap(p))))
                    .orElseGet(() -> buildResponse(404, errorJson("Product not found: " + id)));
        }
        return buildResponse(404, errorJson("Not found: " + path));
    }

    // --- JSON helpers (Map-based, no reflection needed) ---

    private static Map<String, Object> productToMap(Product p) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", p.id());
        map.put("name", p.name());
        map.put("description", p.description());
        map.put("price", p.price());
        map.put("category", p.category());
        if (p.createdAt() != null) {
            map.put("createdAt", p.createdAt().toString());
        }
        return map;
    }

    private static List<Map<String, Object>> productsToList(List<Product> products) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Product p : products) {
            list.add(productToMap(p));
        }
        return list;
    }

    private String okJson(Object data) {
        return toJson(Map.of("success", true, "message", "OK", "data", data));
    }

    private String okJson(String message, Object data) {
        return toJson(Map.of("success", true, "message", message, "data", data));
    }

    private String errorJson(String message) {
        return toJson(Map.of("success", false, "message", message));
    }

    private String toJson(Object obj) {
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            return "{\"success\":false,\"message\":\"Serialization failed\"}";
        }
    }

    private APIGatewayProxyResponseEvent buildResponse(int statusCode, String jsonBody) {
        var response = new APIGatewayProxyResponseEvent();
        response.setStatusCode(statusCode);
        response.setHeaders(CORS_HEADERS);
        response.setBody(jsonBody);
        return response;
    }
}
