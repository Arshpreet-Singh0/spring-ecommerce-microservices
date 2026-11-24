package com.ecommerce.inventory_service.services;

import com.ecommerce.inventory_service.dto.ReserveInventoryDTO;
import com.ecommerce.inventory_service.exceptions.InsufficientStockException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * Plain unit test of the facade (no Spring proxy, so no retries here). The retry behaviour
 * itself is exercised against a real database in InventoryConcurrencyIT.
 */
@ExtendWith(MockitoExtension.class)
class InventoryReservationFacadeTest {

    @Mock
    private InventoryReservationService reservationService;

    @InjectMocks
    private InventoryReservationFacade facade;

    private final ReserveInventoryDTO dto = InventoryReservationServiceTest.request("order-1",
            InventoryReservationServiceTest.item("SKU-A", 1));

    @Test
    void delegatesToTransactionalService() {
        facade.reserveInventory(dto);

        verify(reservationService).reserveInventory(dto);
        assertThat(facade.getOptimisticLockConflicts()).isZero();
    }

    @Test
    void optimisticLockConflict_isCounted_andRethrownForTheRetryInterceptor() {
        doThrow(new ObjectOptimisticLockingFailureException("Inventory", 1L))
                .when(reservationService).reserveInventory(dto);

        assertThatThrownBy(() -> facade.reserveInventory(dto))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
        assertThat(facade.getOptimisticLockConflicts()).isEqualTo(1);
    }

    @Test
    void businessErrors_areNotCountedAsConflicts() {
        doThrow(new InsufficientStockException("SKU-A", 1, 0)).when(reservationService).reserveInventory(dto);

        assertThatThrownBy(() -> facade.reserveInventory(dto)).isInstanceOf(InsufficientStockException.class);
        assertThat(facade.getOptimisticLockConflicts()).isZero();
    }
}
