package com.example.sample;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Spring Data JPA repository for SampleEntity. */
@Repository
public interface SampleRepository extends JpaRepository<SampleEntity, Long> {

  /**
   * Finds all customers matching the given name.
   *
   * @param name the customer name to search for
   * @return list of matching customers
   */
  List<SampleEntity> findByName(String name);
}
