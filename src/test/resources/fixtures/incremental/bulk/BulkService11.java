package com.esmp.incremental.bulk;

import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class BulkService11 {
    public void process(String input) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("input must not be blank");
        }
    }
    public List<String> listItems() {
        return List.of("item-11-a", "item-11-b");
    }
}
