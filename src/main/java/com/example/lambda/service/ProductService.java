package com.example.lambda.service;

import com.example.lambda.model.Product;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Product service with in-memory storage.
 * In a real application, replace with DynamoDB, RDS, etc.
 *
 * Uses Java 21 features: pattern matching, records, etc.
 */
public class ProductService {

    private final Map<String, Product> store = new ConcurrentHashMap<>();

    public ProductService() {
        // Seed some sample data
        seedData();
    }

    private void seedData() {
        var products = List.of(
                Product.create("prod-001", "Wireless Mouse", "Ergonomic wireless mouse with USB-C", 29.99, "Electronics"),
                Product.create("prod-002", "Mechanical Keyboard", "Cherry MX Blue switches, RGB backlit", 89.99, "Electronics"),
                Product.create("prod-003", "USB-C Hub", "7-in-1 USB-C hub with HDMI and Ethernet", 45.00, "Accessories"),
                Product.create("prod-004", "Monitor Stand", "Adjustable aluminum monitor stand", 59.99, "Furniture"),
                Product.create("prod-005", "Desk Lamp", "LED desk lamp with wireless charging base", 39.99, "Lighting")
        );
        products.forEach(p -> store.put(p.id(), p));
    }

    public List<Product> getAllProducts() {
        return List.copyOf(store.values());
    }

    public Optional<Product> getProductById(String id) {
        return Optional.ofNullable(store.get(id));
    }

    public Product createProduct(Product product) {
        String id = "prod-" + UUID.randomUUID().toString().substring(0, 8);
        var newProduct = new Product(id, product.name(), product.description(), product.price(), product.category(), Instant.now());
        store.put(id, newProduct);
        return newProduct;
    }

    public Optional<Product> deleteProduct(String id) {
        return Optional.ofNullable(store.remove(id));
    }

    public List<Product> searchByCategory(String category) {
        return store.values().stream()
                .filter(p -> p.category() != null && p.category().equalsIgnoreCase(category))
                .toList();
    }

    /**
     * Demonstrates Java 21 pattern matching with switch.
     */
    public String getProductSummary(Object input) {
        return switch (input) {
            case String id when id.startsWith("prod-") -> {
                var product = store.get(id);
                yield product != null
                        ? product.name() + " - $" + product.price()
                        : "Product not found: " + id;
            }
            case String name -> "Search by name not yet implemented: " + name;
            case Integer count -> "Returning top " + count + " products";
            case null -> "No input provided";
            default -> "Unsupported input type: " + input.getClass().getSimpleName();
        };
    }
}
