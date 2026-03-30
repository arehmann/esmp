import {
  riskColor, riskLabel, automationColor, automationLabel,
  formatPercent, shortPackageName, simpleNameFromFqn, groupByPackage,
} from "@/lib/utils";

describe("riskColor", () => {
  it("returns green for score < 1.0", () => expect(riskColor(0.5)).toBe("text-risk-low"));
  it("returns amber for score 1.0–2.0", () => expect(riskColor(1.5)).toBe("text-risk-medium"));
  it("returns red for score > 2.0", () => expect(riskColor(3.0)).toBe("text-risk-high"));
  it("returns green for exactly 0", () => expect(riskColor(0)).toBe("text-risk-low"));
});

describe("riskLabel", () => {
  it("returns LOW for < 1.0", () => expect(riskLabel(0.3)).toBe("LOW"));
  it("returns MEDIUM for 1.0–2.0", () => expect(riskLabel(1.5)).toBe("MEDIUM"));
  it("returns HIGH for > 2.0", () => expect(riskLabel(2.5)).toBe("HIGH"));
});

describe("automationColor", () => {
  it("returns green for FULL", () => expect(automationColor("FULL")).toBe("text-auto-full"));
  it("returns amber for YES_AI", () => expect(automationColor("YES_AI")).toBe("text-auto-ai"));
  it("returns red for NO", () => expect(automationColor("NO")).toBe("text-auto-manual"));
});

describe("formatPercent", () => {
  it("formats 0.6789 as 67.9%", () => expect(formatPercent(0.6789)).toBe("67.9%"));
  it("formats 1 as 100.0%", () => expect(formatPercent(1)).toBe("100.0%"));
  it("formats 0 as 0.0%", () => expect(formatPercent(0)).toBe("0.0%"));
});

describe("shortPackageName", () => {
  it("removes common prefix", () => expect(shortPackageName("com.adcubum.adsuitemarkt.vaadin.ui.panel")).toBe("vaadin.ui.panel"));
  it("handles short packages", () => expect(shortPackageName("com.adcubum")).toBe("com.adcubum"));
});

describe("simpleNameFromFqn", () => {
  it("extracts last segment", () => expect(simpleNameFromFqn("com.example.OrderService")).toBe("OrderService"));
  it("handles no dots", () => expect(simpleNameFromFqn("OrderService")).toBe("OrderService"));
});

describe("groupByPackage", () => {
  it("groups entries by packageName", () => {
    const entries = [
      { packageName: "com.a", enhancedRiskScore: 1.0 },
      { packageName: "com.a", enhancedRiskScore: 2.0 },
      { packageName: "com.b", enhancedRiskScore: 0.5 },
    ] as any[];
    const groups = groupByPackage(entries);
    expect(groups).toHaveLength(2);
    expect(groups.find((g) => g.packageName === "com.a")?.classCount).toBe(2);
    expect(groups.find((g) => g.packageName === "com.a")?.avgEnhancedRisk).toBe(1.5);
  });
});
