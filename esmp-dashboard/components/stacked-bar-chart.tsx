"use client";

import {
  BarChart,
  Bar,
  XAxis,
  YAxis,
  Tooltip,
  ResponsiveContainer,
} from "recharts";
import type { PackageGroup } from "@/lib/types";

interface StackedBarChartProps {
  packages: PackageGroup[];
  summaryData?: {
    fullyAutomatableClasses: number;
    partiallyAutomatableClasses: number;
    needsAiOnlyClasses: number;
    totalClasses: number;
  };
}

export function StackedBarChart({ packages }: StackedBarChartProps) {
  // For the overview, show top 10 packages by class count
  const data = packages.slice(0, 10).map((pkg) => ({
    name: pkg.shortName.length > 20 ? pkg.shortName.slice(0, 20) + "..." : pkg.shortName,
    classes: pkg.classCount,
    avgRisk: Number(pkg.avgEnhancedRisk.toFixed(2)),
  }));

  return (
    <ResponsiveContainer width="100%" height={300}>
      <BarChart data={data} layout="vertical" margin={{ left: 120 }}>
        <XAxis type="number" stroke="#64748b" fontSize={12} />
        <YAxis
          type="category"
          dataKey="name"
          stroke="#64748b"
          fontSize={11}
          width={120}
        />
        <Tooltip
          contentStyle={{
            backgroundColor: "#1e293b",
            border: "1px solid #334155",
            borderRadius: "6px",
            fontSize: "12px",
          }}
        />
        <Bar dataKey="classes" fill="#3b82f6" radius={[0, 4, 4, 0]} />
      </BarChart>
    </ResponsiveContainer>
  );
}
