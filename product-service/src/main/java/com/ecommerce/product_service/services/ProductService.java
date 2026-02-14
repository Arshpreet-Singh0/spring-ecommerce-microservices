package com.ecommerce.product_service.services;


import com.ecommerce.product_service.dto.CreateProductRequestDTO;
import com.ecommerce.product_service.dto.ProductResponseDTO;
import com.ecommerce.product_service.dto.UpdateProductRequestDTO;
import com.ecommerce.product_service.entities.Product;
import com.ecommerce.product_service.entities.enums.ProductStatus;
import com.ecommerce.product_service.event.ProductCreatedEvent;
import com.ecommerce.product_service.exceptions.ProductSkuAlreadyExistsException;
import com.ecommerce.product_service.repositories.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.modelmapper.ModelMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;
    private final ModelMapper modelMapper;
    private final KafkaTemplate<String, ProductCreatedEvent> kafkaTemplate;

    @Value("${kafka.topic.product-created-topic}")
    private String KAFKA_PRODUCT_CREATED_TOPIC;

    @Transactional
    public ProductResponseDTO create(CreateProductRequestDTO request) {
        boolean isExist = productRepository.existsBySku(request.getSku());

        if (isExist) {
            throw new ProductSkuAlreadyExistsException("Product with " + request.getSku() + " sku already exists");
        }
        Product product = modelMapper.map(request, Product.class);
        product.setStatus(ProductStatus.ACTIVE);
        Product savedProduct = productRepository.save(product);

        ProductCreatedEvent productCreatedEvent = modelMapper.map(savedProduct, ProductCreatedEvent.class);
        productCreatedEvent.setTotalQuantity(request.getTotalQuantity());

        kafkaTemplate.send(KAFKA_PRODUCT_CREATED_TOPIC, productCreatedEvent.getSku(), productCreatedEvent);

        return modelMapper.map(savedProduct, ProductResponseDTO.class);
    }

    @Transactional
    public ProductResponseDTO update(Long productId, UpdateProductRequestDTO request) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new RuntimeException("Product not found"));

        modelMapper.map(request, product);

        Product updatedProduct = productRepository.save(product);
        return modelMapper.map(updatedProduct, ProductResponseDTO.class);
    }
}