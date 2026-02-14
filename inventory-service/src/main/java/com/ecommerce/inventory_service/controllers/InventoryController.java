package com.ecommerce.inventory_service.controllers;

import com.ecommerce.inventory_service.dto.InventoryResponseDTO;
import com.ecommerce.inventory_service.dto.InventoryUpdateRequest;
import com.ecommerce.inventory_service.services.InventoryService;
import lombok.AllArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/inventory")
@AllArgsConstructor
public class InventoryController {

    private final InventoryService inventoryService;

    @GetMapping("/{sku}")
    public ResponseEntity<InventoryResponseDTO> getInventory(@PathVariable  String sku) {
        return ResponseEntity.ok(inventoryService.getInventory(sku));
    }

    @PutMapping("/update")
    public ResponseEntity<Void> updateInventory(@RequestBody InventoryUpdateRequest request) {

        inventoryService.updateInventory(request);

        return ResponseEntity.ok().build();
    }
}
