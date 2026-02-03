package com.ecommerce.search_service.services;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.ecommerce.product_service.event.ProductCreatedEvent;
import com.ecommerce.search_service.documents.ProductSearchDocument;
import com.ecommerce.search_service.repositories.ProductSearchRepository;
import lombok.RequiredArgsConstructor;
import org.modelmapper.ModelMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SearchService {

    private final ElasticsearchClient elasticsearchClient;
    private final ModelMapper modelMapper;

    public void indexProduct(ProductCreatedEvent event) {

        ProductSearchDocument document =
                modelMapper.map(event, ProductSearchDocument.class);

        try {
            elasticsearchClient.index(i -> i
                    .index("products")
                    .id(document.getSku())   // stable ID
                    .document(document)
            );
        } catch (IOException e) {
            throw new RuntimeException("Failed to index product", e);
        }
    }

//    public List<ProductSearchDocument> searchByName(String query) {
//        return repository.findByNameContainingIgnoreCase(query);
//    }
}