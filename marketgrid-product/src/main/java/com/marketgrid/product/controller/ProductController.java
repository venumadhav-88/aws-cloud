package com.marketgrid.product.controller;

import com.marketgrid.product.config.JwtUtil;
import com.marketgrid.product.entity.Product;
import com.marketgrid.product.service.ProductService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/products")
public class ProductController {

    private final ProductService productService;
    private final JwtUtil jwtUtil;
    private final RestClient vendorClient;

    public ProductController(ProductService productService, JwtUtil jwtUtil, RestClient.Builder restClientBuilder) {
        this.productService = productService;
        this.jwtUtil = jwtUtil;
        // Uses the same @LoadBalanced RestClient.Builder already configured
        this.vendorClient = restClientBuilder.baseUrl("http://marketgrid-vendor").build();
    }

    // Helper: get authenticated email from token
    private String getAuthenticatedEmail(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return jwtUtil.getEmailFromToken(header.substring(7));
        }
        throw new RuntimeException("No valid token found");
    }

    // Helper: fetch the vendor record for the authenticated email
    @SuppressWarnings("unchecked")
    private Long getVendorIdForEmail(String email) {
        try {
            Map<String, Object> vendor = vendorClient.get()
                    .uri("/vendors/by-email?email={email}", email)
                    .retrieve()
                    .body(Map.class);
            if (vendor != null && vendor.containsKey("id")) {
                return Long.valueOf(vendor.get("id").toString());
            }
        } catch (Exception e) {
            throw new RuntimeException("Could not verify vendor identity");
        }
        throw new RuntimeException("No vendor profile found for this account");
    }

    @PostMapping
    public ResponseEntity<?> createProduct(@RequestBody Product product, HttpServletRequest request) {
        try {
            String email = getAuthenticatedEmail(request);
            Long myVendorId = getVendorIdForEmail(email);

            // Force vendorId to authenticated vendor's id
            product.setVendorId(myVendorId);

            return ResponseEntity.ok(productService.createProduct(product));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getProduct(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(productService.getProductById(id));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping
    public List<Product> getAllProducts() {
        return productService.getAllProducts();
    }

    @GetMapping("/vendor/{vendorId}")
    public ResponseEntity<?> getProductsByVendor(@PathVariable Long vendorId, HttpServletRequest request) {
        try {
            String header = request.getHeader("Authorization");
            if (header == null || !header.startsWith("Bearer ")) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "No valid token found"));
            }
            String token = header.substring(7);
            String role = jwtUtil.getRoleFromToken(token);
            if (!"ADMIN".equalsIgnoreCase(role)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Access denied"));
            }
            return ResponseEntity.ok(productService.getProductsByVendorId(vendorId));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Invalid token"));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateProduct(@PathVariable Long id, @RequestBody Product product, HttpServletRequest request) {
        try {
            String email = getAuthenticatedEmail(request);
            Long myVendorId = getVendorIdForEmail(email);

            Product existing = productService.getProductById(id);
            if (!existing.getVendorId().equals(myVendorId)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Access denied"));
            }

            return ResponseEntity.ok(productService.updateProduct(id, product));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteProduct(@PathVariable Long id, HttpServletRequest request) {
        try {
            String email = getAuthenticatedEmail(request);
            Long myVendorId = getVendorIdForEmail(email);

            Product existing = productService.getProductById(id);
            if (!existing.getVendorId().equals(myVendorId)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Access denied"));
            }

            productService.deleteProduct(id);
            return ResponseEntity.ok(Map.of("message", "Product deleted"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/{id}/stock")
    public ResponseEntity<?> reduceStock(@PathVariable Long id, @RequestBody Map<String, Integer> body, HttpServletRequest request) {
        try {
            // 1. Check if this is an internal call from Order Service
            String internalHeader = request.getHeader("X-Internal-Service");
            if (!"order-service".equals(internalHeader)) {
                // 2. If not internal, enforce vendor ownership
                String email = getAuthenticatedEmail(request);
                Long myVendorId = getVendorIdForEmail(email);

                Product existing = productService.getProductById(id);
                if (!existing.getVendorId().equals(myVendorId)) {
                    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Access denied"));
                }
            }
                                                                                       
            int quantityToReduce = body.get("quantity");
            return ResponseEntity.ok(productService.reduceStock(id, quantityToReduce));
        } catch (Exception e) {
            // Return appropriate status based on exception
            if ("Access denied".equals(e.getMessage())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
            } else if ("No valid token found".equals(e.getMessage())) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", e.getMessage()));
            }
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
