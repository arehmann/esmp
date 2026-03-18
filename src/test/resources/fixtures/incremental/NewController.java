package com.esmp.incremental;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

/**
 * New REST controller added in an incremental run.
 * Tests that a genuinely new class (not previously indexed) gets fully extracted and persisted.
 */
@RestController
@RequestMapping("/api/base")
public class NewController {

    private final BaseService baseService;

    public NewController(BaseService baseService) {
        this.baseService = baseService;
    }

    @GetMapping
    public ResponseEntity<List<BaseEntity>> findAll() {
        return ResponseEntity.ok(baseService.findAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<BaseEntity> findById(@PathVariable Long id) {
        return baseService.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<BaseEntity> create(@RequestBody BaseEntity entity) {
        return ResponseEntity.ok(baseService.save(entity));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        baseService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
