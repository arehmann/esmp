package com.esmp.extraction.api;

import com.esmp.extraction.audit.VaadinAuditReport;
import java.util.List;

/**
 * Response body for POST /api/extraction/trigger.
 *
 * <p>Contains counts of all extracted entities, Vaadin-specific pattern counts, any errors
 * encountered, and the Vaadin audit report.
 */
public class ExtractionResponse {

  private int classCount;
  private int methodCount;
  private int fieldCount;
  private int callEdgeCount;
  private int vaadinViewCount;
  private int vaadinComponentCount;
  private int vaadinDataBindingCount;
  private int annotationCount;
  private int packageCount;
  private int moduleCount;
  private int tableCount;
  private int errorCount;
  private List<String> errors;
  private VaadinAuditReport auditReport;
  private long durationMs;

  public ExtractionResponse() {}

  public ExtractionResponse(
      int classCount,
      int methodCount,
      int fieldCount,
      int callEdgeCount,
      int vaadinViewCount,
      int vaadinComponentCount,
      int vaadinDataBindingCount,
      int annotationCount,
      int packageCount,
      int moduleCount,
      int tableCount,
      int errorCount,
      List<String> errors,
      VaadinAuditReport auditReport,
      long durationMs) {
    this.classCount = classCount;
    this.methodCount = methodCount;
    this.fieldCount = fieldCount;
    this.callEdgeCount = callEdgeCount;
    this.vaadinViewCount = vaadinViewCount;
    this.vaadinComponentCount = vaadinComponentCount;
    this.vaadinDataBindingCount = vaadinDataBindingCount;
    this.annotationCount = annotationCount;
    this.packageCount = packageCount;
    this.moduleCount = moduleCount;
    this.tableCount = tableCount;
    this.errorCount = errorCount;
    this.errors = errors;
    this.auditReport = auditReport;
    this.durationMs = durationMs;
  }

  public int getClassCount() {
    return classCount;
  }

  public void setClassCount(int classCount) {
    this.classCount = classCount;
  }

  public int getMethodCount() {
    return methodCount;
  }

  public void setMethodCount(int methodCount) {
    this.methodCount = methodCount;
  }

  public int getFieldCount() {
    return fieldCount;
  }

  public void setFieldCount(int fieldCount) {
    this.fieldCount = fieldCount;
  }

  public int getCallEdgeCount() {
    return callEdgeCount;
  }

  public void setCallEdgeCount(int callEdgeCount) {
    this.callEdgeCount = callEdgeCount;
  }

  public int getVaadinViewCount() {
    return vaadinViewCount;
  }

  public void setVaadinViewCount(int vaadinViewCount) {
    this.vaadinViewCount = vaadinViewCount;
  }

  public int getVaadinComponentCount() {
    return vaadinComponentCount;
  }

  public void setVaadinComponentCount(int vaadinComponentCount) {
    this.vaadinComponentCount = vaadinComponentCount;
  }

  public int getVaadinDataBindingCount() {
    return vaadinDataBindingCount;
  }

  public void setVaadinDataBindingCount(int vaadinDataBindingCount) {
    this.vaadinDataBindingCount = vaadinDataBindingCount;
  }

  public int getAnnotationCount() {
    return annotationCount;
  }

  public void setAnnotationCount(int annotationCount) {
    this.annotationCount = annotationCount;
  }

  public int getPackageCount() {
    return packageCount;
  }

  public void setPackageCount(int packageCount) {
    this.packageCount = packageCount;
  }

  public int getModuleCount() {
    return moduleCount;
  }

  public void setModuleCount(int moduleCount) {
    this.moduleCount = moduleCount;
  }

  public int getTableCount() {
    return tableCount;
  }

  public void setTableCount(int tableCount) {
    this.tableCount = tableCount;
  }

  public int getErrorCount() {
    return errorCount;
  }

  public void setErrorCount(int errorCount) {
    this.errorCount = errorCount;
  }

  public List<String> getErrors() {
    return errors;
  }

  public void setErrors(List<String> errors) {
    this.errors = errors;
  }

  public VaadinAuditReport getAuditReport() {
    return auditReport;
  }

  public void setAuditReport(VaadinAuditReport auditReport) {
    this.auditReport = auditReport;
  }

  public long getDurationMs() {
    return durationMs;
  }

  public void setDurationMs(long durationMs) {
    this.durationMs = durationMs;
  }
}
