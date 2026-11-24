package com.ecommerce.inventory_service.services;

import com.ecommerce.inventory_service.dto.ReserveInventoryDTO;
import com.ecommerce.inventory_service.entities.Inventory;
import com.ecommerce.inventory_service.entities.InventoryReservation;
import com.ecommerce.inventory_service.entities.enums.Status;
import com.ecommerce.inventory_service.exceptions.InsufficientStockException;
import com.ecommerce.inventory_service.exceptions.SkuNotFoundException;
import com.ecommerce.inventory_service.repositories.InventoryRepository;
import com.ecommerce.inventory_service.repositories.InventoryReservationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static java.time.temporal.ChronoUnit.SECONDS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InventoryReservationServiceTest {

    @Mock
    private InventoryReservationRepository reservationRepository;
    @Mock
    private InventoryRepository inventoryRepository;

    @InjectMocks
    private InventoryReservationService service;

    @Test
    void reserve_increasesReservedQuantity_andCreatesReservationsExpiringIn5Minutes() {
        Inventory a = inventory("SKU-A", 10, 2);
        Inventory b = inventory("SKU-B", 5, 0);
        when(reservationRepository.existsByOrderIdAndStatusIn(eq("order-1"), anyCollection())).thenReturn(false);
        when(inventoryRepository.findBySkuIn(List.of("SKU-A", "SKU-B"))).thenReturn(List.of(a, b));

        service.reserveInventory(request("order-1", item("SKU-A", 3), item("SKU-B", 5)));

        assertThat(a.getReservedQuantity()).isEqualTo(5);
        assertThat(b.getReservedQuantity()).isEqualTo(5);
        verify(inventoryRepository).saveAll(List.of(a, b));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<InventoryReservation>> captor = ArgumentCaptor.forClass(List.class);
        verify(reservationRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(2).allSatisfy(r -> {
            assertThat(r.getOrderId()).isEqualTo("order-1");
            assertThat(r.getStatus()).isEqualTo(Status.RESERVED);
            assertThat(r.getExpiresAt()).isCloseTo(LocalDateTime.now().plusMinutes(5), within(5, SECONDS));
        });
        assertThat(captor.getValue()).extracting(InventoryReservation::getSku, InventoryReservation::getQuantity)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("SKU-A", 3),
                        org.assertj.core.groups.Tuple.tuple("SKU-B", 5));
    }

    @Test
    void insufficientStock_throws_andWritesNothing() {
        Inventory a = inventory("SKU-A", 10, 0);
        Inventory b = inventory("SKU-B", 2, 1); // only 1 available
        when(reservationRepository.existsByOrderIdAndStatusIn(anyString(), anyCollection())).thenReturn(false);
        when(inventoryRepository.findBySkuIn(any())).thenReturn(List.of(a, b));

        assertThatThrownBy(() -> service.reserveInventory(request("order-1", item("SKU-A", 1), item("SKU-B", 2))))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessageContaining("SKU-B");

        // no partial writes: the first (valid) item is not reserved either
        assertThat(a.getReservedQuantity()).isZero();
        verify(inventoryRepository, never()).saveAll(any());
        verify(reservationRepository, never()).saveAll(any());
    }

    @Test
    void unknownSku_throwsSkuNotFound() {
        when(reservationRepository.existsByOrderIdAndStatusIn(anyString(), anyCollection())).thenReturn(false);
        when(inventoryRepository.findBySkuIn(any())).thenReturn(List.of());

        assertThatThrownBy(() -> service.reserveInventory(request("order-1", item("NOPE", 1))))
                .isInstanceOf(SkuNotFoundException.class)
                .hasMessageContaining("NOPE");

        verify(inventoryRepository, never()).saveAll(any());
        verify(reservationRepository, never()).saveAll(any());
    }

    @Test
    void existingActiveReservationForOrder_isNoOp() {
        when(reservationRepository.existsByOrderIdAndStatusIn("order-1", List.of(Status.RESERVED, Status.CONFIRMED)))
                .thenReturn(true);

        service.reserveInventory(request("order-1", item("SKU-A", 1)));

        verifyNoInteractions(inventoryRepository);
        verify(reservationRepository, never()).saveAll(any());
    }

    static Inventory inventory(String sku, int total, int reserved) {
        return Inventory.builder()
                .sku(sku)
                .totalQuantity(total)
                .reservedQuantity(reserved)
                .availableQuantity(total - reserved)
                .version(0L)
                .build();
    }

    static ReserveInventoryDTO.Item item(String sku, int qty) {
        ReserveInventoryDTO.Item item = new ReserveInventoryDTO.Item();
        item.setSku(sku);
        item.setQty(qty);
        return item;
    }

    static ReserveInventoryDTO request(String orderId, ReserveInventoryDTO.Item... items) {
        ReserveInventoryDTO dto = new ReserveInventoryDTO();
        dto.setOrderId(orderId);
        dto.setItems(List.of(items));
        return dto;
    }
}
