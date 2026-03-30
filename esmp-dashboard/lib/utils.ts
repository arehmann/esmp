import { clsx, type ClassValue } from "clsx"
import { twMerge } from "tailwind-merge"
import type { RiskHeatmapEntry, PackageGroup } from "./types"

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs))
}

// --- Risk colors ---
export function riskColor(score: number): string {
  if (score < 1.0) return "text-risk-low";
  if (score <= 2.0) return "text-risk-medium";
  return "text-risk-high";
}

export function riskBgColor(score: number): string {
  if (score < 1.0) return "bg-risk-low";
  if (score <= 2.0) return "bg-risk-medium";
  return "bg-risk-high";
}

export function riskLabel(score: number): string {
  if (score < 1.0) return "LOW";
  if (score <= 2.0) return "MEDIUM";
  return "HIGH";
}

export function riskHex(score: number): string {
  if (score < 1.0) return "#22c55e";
  if (score <= 2.0) return "#f59e0b";
  return "#ef4444";
}

// --- Automation colors ---
export function automationColor(automatable: string): string {
  switch (automatable) {
    case "FULL": case "YES": return "text-auto-full";
    case "YES_AI": case "PARTIAL": return "text-auto-ai";
    default: return "text-auto-manual";
  }
}

export function automationBgColor(automatable: string): string {
  switch (automatable) {
    case "FULL": case "YES": return "bg-auto-full";
    case "YES_AI": case "PARTIAL": return "bg-auto-ai";
    default: return "bg-auto-manual";
  }
}

export function automationLabel(automatable: string): string {
  switch (automatable) {
    case "FULL": case "YES": return "Auto";
    case "YES_AI": case "PARTIAL": return "AI-Assisted";
    default: return "Manual";
  }
}

// --- Formatters ---
export function formatPercent(value: number): string {
  return `${(value * 100).toFixed(1)}%`;
}

export function formatNumber(value: number): string {
  return value.toLocaleString();
}

// --- Package helpers ---
export function shortPackageName(pkg: string): string {
  const parts = pkg.split(".");
  if (parts.length > 3 && parts[0] === "com") {
    return parts.slice(3).join(".");
  }
  return pkg;
}

export function simpleNameFromFqn(fqn: string): string {
  const idx = fqn.lastIndexOf(".");
  return idx >= 0 ? fqn.substring(idx + 1) : fqn;
}

export function groupByPackage(entries: RiskHeatmapEntry[]): PackageGroup[] {
  const map = new Map<string, RiskHeatmapEntry[]>();
  for (const e of entries) {
    const arr = map.get(e.packageName) ?? [];
    arr.push(e);
    map.set(e.packageName, arr);
  }
  return Array.from(map.entries()).map(([pkg, classes]) => ({
    packageName: pkg,
    shortName: shortPackageName(pkg),
    classCount: classes.length,
    avgEnhancedRisk: classes.reduce((sum, c) => sum + c.enhancedRiskScore, 0) / classes.length,
    classes,
  }));
}
