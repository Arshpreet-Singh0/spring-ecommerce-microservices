package com.ecommerce.inventory_service.services;

import com.ecommerce.inventory_service.dto.InventoryResponseDTO;
import com.ecommerce.inventory_service.dto.InventoryUpdateRequest;
import com.ecommerce.inventory_service.entities.Inventory;
import com.ecommerce.inventory_service.event.InventoryUpdatedEvent;
import com.ecommerce.inventory_service.event.ProductCreatedEvent;
import com.ecommerce.inventory_service.exceptions.SkuNotFoundException;
import com.ecommerce.inventory_service.repositories.InventoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.modelmapper.ModelMapper;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InventoryServiceTest {

    private static final String TOPIC = "inventory-updated-topic";

    @Mock
    private InventoryRepository inventoryRepository;
    @Mock
    private KafkaTemplate<String, InventoryUpdatedEvent> kafkaTemplate;

    private InventoryService service;

    @BeforeEach
    void setUp() {
        service = new InventoryService(inventoryRepository, new ModelMapper(), kafkaTemplate);
        ReflectionTestUtils.setField(service, "KAFKA_INVENTORY_UPDATED_TOPIC", TOPIC);
    }

    @Test
    void createInventory_fromEvent_startsWithNothingReserved() {
        when(inventoryRepository.findBySku("SKU-A")).thenReturn(Optional.empty());
        when(inventoryRepository.save(any(Inventory.class))).thenAnswer(inv -> inv.getArgument(0));

        service.createInventory(productCreated(99L, "SKU-A", 25));

        ArgumentCaptor<Inventory> captor = ArgumentCaptor.forClass(Inventory.class);
        verify(inventoryRepository).save(captor.capture());
        Inventory saved = captor.getValue();
        assertThat(saved.getId()).as("product id must not leak into the inventory id").isNull();
        assertThat(saved.getSku()).isEqualTo("SKU-A");
        assertThat(saved.getTotalQuantity()).isEqualTo(25);
        assertThat(saved.getReservedQuantity()).isZero();
    }

    @Test
    void createInventory_isIdempotent_existingSkuIsSkipped() {
        when(inventoryRepository.findBySku("SKU-A")).thenReturn(Optional.of(new Inventory()));

        service.createInventory(productCreated(1L, "SKU-A", 25));

        verify(inventoryRepository, never()).save(any());
    }

    @Test
    void updateInventory_publishesEventWithNewAvailableQuantity() {
        Inventory inv = Inventory.builder().sku("SKU-A").totalQuantity(10).reservedQuantity(3).availableQuantity(7).build();
        when(inventoryRepository.findBySku("SKU-A")).thenReturn(Optional.of(inv));

        service.updateInventory(update("SKU-A", 20));

        assertThat(inv.getTotalQuantity()).isEqualTo(20);
        verify(inventoryRepository).save(inv);
        ArgumentCaptor<InventoryUpdatedEvent> event = ArgumentCaptor.forClass(InventoryUpdatedEvent.class);
        verify(kafkaTemplate).send(org.mockito.ArgumentMatchers.eq(TOPIC), org.mockito.ArgumentMatchers.eq("SKU-A"), event.capture());
        assertThat(event.getValue().getSku()).isEqualTo("SKU-A");
        assertThat(event.getValue().getQuantity()).isEqualTo(17); // 20 total - 3 reserved
    }

    @Test
    void updateInventory_unknownSku_throws_andPublishesNothing() {
        when(inventoryRepository.findBySku("NOPE")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateInventory(update("NOPE", 5)))
                .isInstanceOf(SkuNotFoundException.class);
        verifyNoInteractions(kafkaTemplate);
    }

    @Test
    void getInventory_mapsEntity_orThrowsForUnknownSku() {
        Inventory inv = Inventory.builder().id(1L).sku("SKU-A").totalQuantity(10).reservedQuantity(4).availableQuantity(6).build();
        when(inventoryRepository.findBySku("SKU-A")).thenReturn(Optional.of(inv));
        when(inventoryRepository.findBySku("NOPE")).thenReturn(Optional.empty());

        InventoryResponseDTO dto = service.getInventory("SKU-A");

        assertThat(dto.getSku()).isEqualTo("SKU-A");
        assertThat(dto.getAvailableQuantity()).isEqualTo(6);
        assertThatThrownBy(() -> service.getInventory("NOPE")).isInstanceOf(SkuNotFoundException.class);
        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
    }

    private static ProductCreatedEvent productCreated(Long id, String sku, int qty) {
        ProductCreatedEvent e = new ProductCreatedEvent();
        e.setId(id);
        e.setSku(sku);
        e.setName("Trail shoe");
        e.setTotalQuantity(qty);
        return e;
    }

    private static InventoryUpdateRequest update(String sku, int qty) {
        InventoryUpdateRequest r = new InventoryUpdateRequest();
        r.setSku(sku);
        r.setQuantity(qty);
        return r;
    }
}
