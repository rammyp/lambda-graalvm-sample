package com.example.lambda.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * Product model using Java 21 record.
 * Records work well with GraalVM native image since they have
 * a well-defined constructor and accessor pattern.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Product(
        String id,
        String name,
        String description,
        double price,
        String category,
        Instant createdAt
) {
    /**
     * Compact constructor with validation.
     */
    public Product {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Product name cannot be blank");
        }
        if (price < 0) {
            throw new IllegalArgumentException("Price cannot be negative");
        }
    }

    /**
     * Convenience factory for creating a product with auto-generated fields.
     */
    public static Product create(String id, String name, String description, double price, String category) {
        return new Product(id, name, description, price, category, Instant.now());
    }
}
