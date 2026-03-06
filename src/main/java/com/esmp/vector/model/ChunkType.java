package com.esmp.vector.model;

/** Discriminates between the two chunk types produced by {@link com.esmp.vector.application.ChunkingService}. */
public enum ChunkType {
  /** One per class: package, imports, fields, annotations, class-level Javadoc. */
  CLASS_HEADER,

  /** One per method declared in the class. */
  METHOD
}
