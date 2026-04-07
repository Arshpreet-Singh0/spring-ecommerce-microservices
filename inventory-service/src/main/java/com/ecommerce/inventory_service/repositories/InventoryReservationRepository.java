package com.ecommerce.inventory_service.repositories;

import com.ecommerce.inventory_service.entities.InventoryReservation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InventoryReservationRepository extends JpaRepository<InventoryReservation, Long> {
}