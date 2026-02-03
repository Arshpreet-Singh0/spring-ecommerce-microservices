package com.ecommerce.search_service.controllers;

import com.ecommerce.search_service.documents.ProductSearchDocument;
import com.ecommerce.search_service.services.SearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/search")
@RequiredArgsConstructor
public class SearchController {

//    private final SearchService searchService;
//
//    @GetMapping
//    public List<ProductSearchDocument> search(@RequestParam String q) {
////        return searchService.searchByName(q);
//    }
}
