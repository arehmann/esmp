package com.esmp.incremental;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Optional;

/**
 * Base service for incremental indexing tests.
 * Exercises Service stereotype extraction and method-level CC calculation.
 */
@Service
@Transactional
public class BaseService {

    private final BaseRepository baseRepository;

    public BaseService(BaseRepository baseRepository) {
        this.baseRepository = baseRepository;
    }

    public Optional<BaseEntity> findById(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("id must not be null");
        }
        return baseRepository.findById(id);
    }

    public List<BaseEntity> findAll() {
        return baseRepository.findAll();
    }

    public BaseEntity save(BaseEntity entity) {
        if (entity == null) {
            throw new IllegalArgumentException("entity must not be null");
        }
        return baseRepository.save(entity);
    }
}
