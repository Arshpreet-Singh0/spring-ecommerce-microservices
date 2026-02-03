package com.ecommerce.search_service.repositories;

import com.ecommerce.search_service.documents.ProductSearchDocument;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

import java.util.List;

public interface ProductSearchRepository
        extends ElasticsearchRepository<ProductSearchDocument, String> {

    List<ProductSearchDocument> findByNameContainingIgnoreCase(String name);

    List<ProductSearchDocument> findByCategoryAndAvailable(
            String category,
            boolean available
    );
}

