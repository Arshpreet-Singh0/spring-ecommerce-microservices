package com.ecommerce.inventory_service.services;

import com.ecommerce.inventory_service.dto.ReserveInventoryDTO;
import com.ecommerce.inventory_service.entities.Inventory;
import com.ecommerce.inventory_service.exceptions.InsufficientStockException;
import com.ecommerce.inventory_service.repositories.InventoryRepository;
import com.ecommerce.inventory_service.repositories.InventoryReservationRepository;
import com.ecommerce.inventory_service.support.AbstractIntegrationTest;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Showcase test: 50 concurrent single-unit reservations against a SKU with 10 in stock, on a
 * real Postgres. Exactly 10 must succeed and 40 must be rejected for insufficient stock, with no
 * overselling and no reservation rows beyond the 10 winners.
 * <p>
 * Contention is resolved by {@code @Version} on {@code Inventory} plus the retry in
 * {@link InventoryReservationFacade}: losers of an optimistic-lock race re-read the row in a new
 * transaction and either reserve or fail fast once stock is gone.
 */
@Slf4j
class InventoryConcurrencyIT extends AbstractIntegrationTest {

    private static final int THREADS = 50;
    private static final int STOCK = 10;
    private static final String SKU = "CONC-SKU-1";

    @Autowired
    private InventoryReservationFacade reservationFacade;
    @Autowired
    private InventoryRepository inventoryRepository;
    @Autowired
    private InventoryReservationRepository reservationRepository;

    @BeforeEach
    void seed() {
        reservationRepository.deleteAll();
        inventoryRepository.deleteAll();
        inventoryRepository.save(Inventory.builder()
                .sku(SKU).totalQuantity(STOCK).reservedQuantity(0).build());
    }

    @Test
    void fiftyConcurrentReservations_neverOversell() throws Exception {
        long conflictsBefore = reservationFacade.getOptimisticLockConflicts();

        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger insufficientStock = new AtomicInteger();
        List<Throwable> unexpected = Collections.synchronizedList(new ArrayList<>());

        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < THREADS; i++) {
                String orderId = "order-" + i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        reservationFacade.reserveInventory(reserve(orderId));
                        succeeded.incrementAndGet();
                    } catch (InsufficientStockException e) {
                        insufficientStock.incrementAndGet();
                    } catch (Throwable t) {
                        unexpected.add(t);
                    }
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).as("all threads ready").isTrue();
            start.countDown(); // release all 50 at once
            for (Future<?> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        long conflicts = reservationFacade.getOptimisticLockConflicts() - conflictsBefore;
        log.info("Concurrency result: succeeded={}, insufficientStock={}, unexpected={}, optimisticLockRetries={}",
                succeeded.get(), insufficientStock.get(), unexpected.size(), conflicts);

        assertThat(unexpected).as("unexpected failures (e.g. retries exhausted)").isEmpty();
        assertThat(succeeded.get()).isEqualTo(STOCK);
        assertThat(insufficientStock.get()).isEqualTo(THREADS - STOCK);

        Inventory inventory = inventoryRepository.findBySku(SKU).orElseThrow();
        assertThat(inventory.getReservedQuantity()).isEqualTo(STOCK);
        assertThat(inventory.getAvailableQuantity()).isZero();
        assertThat(reservationRepository.count()).isEqualTo(STOCK);
    }

    private static ReserveInventoryDTO reserve(String orderId) {
        ReserveInventoryDTO.Item item = new ReserveInventoryDTO.Item();
        item.setSku(SKU);
        item.setQty(1);
        ReserveInventoryDTO dto = new ReserveInventoryDTO();
        dto.setOrderId(orderId);
        dto.setItems(List.of(item));
        return dto;
    }
}
