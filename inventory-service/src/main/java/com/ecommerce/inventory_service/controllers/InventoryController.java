package com.ecommerce.inventory_service.controllers;

import com.ecommerce.inventory_service.dto.InventoryResponseDTO;
import com.ecommerce.inventory_service.dto.InventoryUpdateRequest;
import com.ecommerce.inventory_service.dto.ReserveInventoryDTO;
import com.ecommerce.inventory_service.services.InventoryReservationFacade;
import com.ecommerce.inventory_service.services.InventoryService;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/inventory")
@AllArgsConstructor
public class InventoryController {

    private final InventoryService inventoryService;
    private final InventoryReservationFacade inventoryReservationFacade;

    @GetMapping("/{sku}")
    public ResponseEntity<InventoryResponseDTO> getInventory(@PathVariable  String sku) {
        return ResponseEntity.ok(inventoryService.getInventory(sku));
    }

    @PutMapping("/update")
    public ResponseEntity<Void> updateInventory(@RequestBody InventoryUpdateRequest request) {

        inventoryService.updateInventory(request);

        return ResponseEntity.ok().build();
    }

    @PostMapping("/reserve")
    public ResponseEntity<Void> reserveInventory(@RequestBody @Valid ReserveInventoryDTO reserveInventoryDTO) {
        log.info("Received request to reserve inventory {}", reserveInventoryDTO);
        inventoryReservationFacade.reserveInventory(reserveInventoryDTO);
        return ResponseEntity.ok().build();
    }
}
