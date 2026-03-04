package com.example.sample;

import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Spring service layer for customer operations. */
@Service
@Transactional
public class SampleService {

  @Autowired private SampleRepository repository;

  /**
   * Returns all customers.
   *
   * @return list of all customers
   */
  public List<SampleEntity> findAll() {
    return repository.findAll();
  }

  /**
   * Finds customers matching the given name.
   *
   * @param name the customer name to search for
   * @return list of matching customers
   */
  public List<SampleEntity> findByName(String name) {
    return repository.findByName(name);
  }

  /**
   * Persists a customer entity.
   *
   * @param entity the customer to save
   * @return the saved customer
   */
  public SampleEntity save(SampleEntity entity) {
    return repository.save(entity);
  }
}
