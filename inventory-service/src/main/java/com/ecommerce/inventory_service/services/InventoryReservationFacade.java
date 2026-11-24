package com.ecommerce.inventory_service.services;

import com.ecommerce.inventory_service.dto.ReserveInventoryDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Entry point for reservations: retries a reservation that lost an optimistic-lock race.
 * <p>
 * The retry deliberately sits on a separate bean <em>around</em> the transaction in
 * {@link InventoryReservationService#reserveInventory}. Each attempt therefore starts a fresh
 * transaction and re-reads the {@code Inventory} rows with their current {@code @Version};
 * retrying inside the same transaction would reuse the stale entities and fail again.
 * <p>
 * Concurrent reservations of the same SKU are serialized by {@code @Version}: one commits,
 * the others fail on commit and are retried here with jittered exponential backoff
 * (20ms, 40ms, 80ms, 160ms, each +/- 10ms). Once stock runs out, a retry fails fast with
 * {@code InsufficientStockException}, which is not retried.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryReservationFacade {

    private final InventoryReservationService inventoryReservationService;

    /** Optimistic-lock conflicts seen so far (each one triggers a retry while attempts remain). */
    private final AtomicLong optimisticLockConflicts = new AtomicLong();

    @Retryable(
            includes = OptimisticLockingFailureException.class,
            maxRetries = 4,
            delay = 20,
            jitter = 10,
            multiplier = 2
    )
    public void reserveInventory(ReserveInventoryDTO dto) {
        try {
            inventoryReservationService.reserveInventory(dto);
        } catch (OptimisticLockingFailureException ex) {
            long total = optimisticLockConflicts.incrementAndGet();
            log.debug("Optimistic-lock conflict reserving order {} (conflicts so far: {})", dto.getOrderId(), total);
            throw ex;
        }
    }

    public long getOptimisticLockConflicts() {
        return optimisticLockConflicts.get();
    }
}
