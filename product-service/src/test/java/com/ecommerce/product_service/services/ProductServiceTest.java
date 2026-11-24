package com.ecommerce.product_service.services;

import com.ecommerce.product_service.dto.CreateProductRequestDTO;
import com.ecommerce.product_service.dto.ProductResponseDTO;
import com.ecommerce.product_service.dto.UpdateProductRequestDTO;
import com.ecommerce.product_service.entities.Product;
import com.ecommerce.product_service.entities.enums.ProductStatus;
import com.ecommerce.product_service.event.ProductCreatedEvent;
import com.ecommerce.product_service.exceptions.ProductSkuAlreadyExistsException;
import com.ecommerce.product_service.repositories.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.modelmapper.ModelMapper;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    private static final String TOPIC = "product-created-topic";

    @Mock
    private ProductRepository productRepository;
    @Mock
    private KafkaTemplate<String, ProductCreatedEvent> kafkaTemplate;

    private ProductService productService;

    @BeforeEach
    void setUp() {
        productService = new ProductService(productRepository, new ModelMapper(), kafkaTemplate);
        ReflectionTestUtils.setField(productService, "KAFKA_PRODUCT_CREATED_TOPIC", TOPIC);
    }

    @Test
    void create_savesActiveProduct_andPublishesEventKeyedBySkuWithTotalQuantity() {
        when(productRepository.existsBySku("TRAIL-01")).thenReturn(false);
        when(productRepository.save(any(Product.class))).thenAnswer(inv -> {
            Product p = inv.getArgument(0);
            p.setId(11L);
            return p;
        });

        ProductResponseDTO response = productService.create(createRequest("TRAIL-01", 25));

        ArgumentCaptor<Product> saved = ArgumentCaptor.forClass(Product.class);
        verify(productRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(ProductStatus.ACTIVE);

        ArgumentCaptor<ProductCreatedEvent> event = ArgumentCaptor.forClass(ProductCreatedEvent.class);
        verify(kafkaTemplate).send(eq(TOPIC), eq("TRAIL-01"), event.capture());
        assertThat(event.getValue().getId()).isEqualTo(11L);
        assertThat(event.getValue().getSku()).isEqualTo("TRAIL-01");
        assertThat(event.getValue().getTotalQuantity()).isEqualTo(25);
        assertThat(event.getValue().getPrice()).isEqualByComparingTo("120.00");

        assertThat(response.getId()).isEqualTo(11L);
        assertThat(response.getSku()).isEqualTo("TRAIL-01");
    }

    @Test
    void create_duplicateSku_throws_andPublishesNothing() {
        when(productRepository.existsBySku("TRAIL-01")).thenReturn(true);

        assertThatThrownBy(() -> productService.create(createRequest("TRAIL-01", 25)))
                .isInstanceOf(ProductSkuAlreadyExistsException.class)
                .hasMessageContaining("TRAIL-01");
        verify(productRepository, never()).save(any());
        verifyNoInteractions(kafkaTemplate);
    }

    @Test
    void findAllByIds_mapsEveryProduct() {
        when(productRepository.findAllById(List.of(1L, 2L)))
                .thenReturn(List.of(product(1L, "A-1"), product(2L, "B-2")));

        List<ProductResponseDTO> result = productService.findAllByIds(List.of(1L, 2L));

        assertThat(result).extracting(ProductResponseDTO::getSku).containsExactly("A-1", "B-2");
    }

    @Test
    void update_appliesChanges_orThrowsForUnknownProduct() {
        Product existing = product(1L, "A-1");
        when(productRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(productRepository.findById(2L)).thenReturn(Optional.empty());
        when(productRepository.save(existing)).thenReturn(existing);
        UpdateProductRequestDTO request = UpdateProductRequestDTO.builder()
                .name("Renamed").category("shoes").brand("Acme").isVisible(false).build();

        ProductResponseDTO response = productService.update(1L, request);

        assertThat(response.getName()).isEqualTo("Renamed");
        assertThat(existing.getIsVisible()).isFalse();
        assertThatThrownBy(() -> productService.update(2L, request)).isInstanceOf(RuntimeException.class);
    }

    private static CreateProductRequestDTO createRequest(String sku, int qty) {
        return CreateProductRequestDTO.builder()
                .name("Trail runner")
                .description("Lightweight trail shoe")
                .sku(sku)
                .price(new BigDecimal("120.00"))
                .discountPercent(new BigDecimal("10"))
                .currency("USD")
                .category("shoes")
                .brand("Acme")
                .totalQuantity(qty)
                .imageUrls(List.of("https://img/1.png"))
                .build();
    }

    private static Product product(Long id, String sku) {
        return Product.builder().id(id).sku(sku).name("P " + sku).price(BigDecimal.TEN)
                .category("shoes").status(ProductStatus.ACTIVE).isVisible(true).build();
    }
}
