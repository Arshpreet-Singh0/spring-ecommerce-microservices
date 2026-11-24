package com.ecommerce.inventory_service.schedulers;

import com.ecommerce.inventory_service.entities.Inventory;
import com.ecommerce.inventory_service.entities.InventoryReservation;
import com.ecommerce.inventory_service.entities.enums.Status;
import com.ecommerce.inventory_service.repositories.InventoryRepository;
import com.ecommerce.inventory_service.repositories.InventoryReservationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReservationCleanupSchedulerTest {

    @Mock
    private InventoryReservationRepository reservationRepository;
    @Mock
    private InventoryRepository inventoryRepository;

    @InjectMocks
    private ReservationCleanupScheduler scheduler;

    @Test
    void expiredReservations_restoreReservedQuantity_andAreMarkedExpired() {
        Inventory inv = Inventory.builder().sku("SKU-A").totalQuantity(10).reservedQuantity(5).availableQuantity(5).build();
        InventoryReservation r1 = reservation("o1", "SKU-A", 2);
        InventoryReservation r2 = reservation("o2", "SKU-A", 3);
        when(reservationRepository.findByStatusAndExpiresAtBefore(eq(Status.RESERVED), any(LocalDateTime.class)))
                .thenReturn(List.of(r1, r2));
        when(inventoryRepository.findBySku("SKU-A")).thenReturn(Optional.of(inv));

        scheduler.releaseExpiredReservations();

        assertThat(inv.getReservedQuantity()).isZero();
        assertThat(r1.getStatus()).isEqualTo(Status.EXPIRED);
        assertThat(r2.getStatus()).isEqualTo(Status.EXPIRED);
        verify(reservationRepository).save(r1);
        verify(reservationRepository).save(r2);
    }

    @Test
    void oneFailingReservation_doesNotStopTheOthers() {
        Inventory inv = Inventory.builder().sku("SKU-B").totalQuantity(10).reservedQuantity(4).availableQuantity(6).build();
        InventoryReservation broken = reservation("o1", "GONE", 1);   // inventory row missing
        InventoryReservation ok = reservation("o2", "SKU-B", 4);
        when(reservationRepository.findByStatusAndExpiresAtBefore(eq(Status.RESERVED), any(LocalDateTime.class)))
                .thenReturn(List.of(broken, ok));
        when(inventoryRepository.findBySku("GONE")).thenReturn(Optional.empty());
        when(inventoryRepository.findBySku("SKU-B")).thenReturn(Optional.of(inv));

        scheduler.releaseExpiredReservations();

        assertThat(broken.getStatus()).isEqualTo(Status.RESERVED);
        verify(reservationRepository, never()).save(broken);
        assertThat(ok.getStatus()).isEqualTo(Status.EXPIRED);
        assertThat(inv.getReservedQuantity()).isZero();
        verify(inventoryRepository).save(inv);
    }

    @Test
    void nothingExpired_writesNothing() {
        when(reservationRepository.findByStatusAndExpiresAtBefore(eq(Status.RESERVED), any(LocalDateTime.class)))
                .thenReturn(List.of());

        scheduler.releaseExpiredReservations();

        verify(inventoryRepository, never()).save(any());
        verify(reservationRepository, never()).save(any());
    }

    private static InventoryReservation reservation(String orderId, String sku, int qty) {
        return InventoryReservation.builder()
                .orderId(orderId).sku(sku).quantity(qty)
                .status(Status.RESERVED)
                .expiresAt(LocalDateTime.now().minusMinutes(1))
                .build();
    }
}
