"use client";

import {
  BarChart,
  Bar,
  XAxis,
  YAxis,
  Tooltip,
  ResponsiveContainer,
} from "recharts";
import type { RecipeRule } from "@/lib/types";

export function HorizontalBarChart({ gaps }: { gaps: RecipeRule[] }) {
  const data = gaps.slice(0, 15).map((g) => ({
    name: g.source.split(".").pop() ?? g.source,
    usageCount: g.usageCount,
    fullSource: g.source,
  }));

  return (
    <ResponsiveContainer width="100%" height={300}>
      <BarChart data={data} layout="vertical" margin={{ left: 100 }}>
        <XAxis type="number" stroke="#64748b" fontSize={12} />
        <YAxis
          type="category"
          dataKey="name"
          stroke="#64748b"
          fontSize={11}
          width={100}
        />
        <Tooltip
          contentStyle={{
            backgroundColor: "#1e293b",
            border: "1px solid #334155",
            borderRadius: "6px",
            fontSize: "12px",
          }}
          formatter={(value: any, _name: any, props: any) => [
            `${value} usages`,
            props.payload.fullSource,
          ]}
        />
        <Bar dataKey="usageCount" fill="#f59e0b" radius={[0, 4, 4, 0]} />
      </BarChart>
    </ResponsiveContainer>
  );
}
