package com.esmp.incremental;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Optional;

/**
 * Modified version of BaseService with an additional method.
 * This file has the same FQN (com.esmp.incremental.BaseService) but different content,
 * so its SHA-256 hash will differ — used to simulate a file change in incremental tests.
 *
 * Usage: copy this file over BaseService.java in the temp dir to simulate a modification.
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

    /**
     * New method added in the modified version to trigger hash change detection.
     * Simulates the addition of a new business method to an existing service class.
     */
    public void delete(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("id must not be null");
        }
        baseRepository.deleteById(id);
    }

    public long count() {
        return baseRepository.count();
    }
}
