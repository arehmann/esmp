package com.esmp.incremental.bulk;

import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class BulkService30 {
    public void process(String input) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("input must not be blank");
        }
    }
    public List<String> listItems() {
        return List.of("item-30-a", "item-30-b");
    }
}
