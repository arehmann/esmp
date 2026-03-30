"use client";

import { Treemap, ResponsiveContainer, Tooltip } from "recharts";
import type { PackageGroup } from "@/lib/types";
import { riskHex } from "@/lib/utils";

interface PackageTreemapProps {
  packages: PackageGroup[];
  onSelectPackage: (packageName: string) => void;
}

interface TreemapNodeProps {
  x: number;
  y: number;
  width: number;
  height: number;
  name: string;
  fill: string;
}

function CustomNode({ x, y, width, height, name, fill }: TreemapNodeProps) {
  if (width < 30 || height < 20) return null;
  return (
    <g>
      <rect
        x={x}
        y={y}
        width={width}
        height={height}
        fill={fill}
        stroke="#1e293b"
        strokeWidth={2}
        rx={4}
        className="cursor-pointer transition-opacity hover:opacity-80"
      />
      {width > 60 && height > 30 && (
        <text
          x={x + width / 2}
          y={y + height / 2}
          textAnchor="middle"
          dominantBaseline="middle"
          fill="#fff"
          fontSize={Math.min(12, width / 8)}
          fontWeight={500}
        >
          {name.length > width / 7 ? name.slice(0, Math.floor(width / 7)) + "..." : name}
        </text>
      )}
    </g>
  );
}

export function PackageTreemap({
  packages,
  onSelectPackage,
}: PackageTreemapProps) {
  const data = packages.map((pkg) => ({
    name: pkg.shortName,
    size: pkg.classCount,
    fill: riskHex(pkg.avgEnhancedRisk),
    packageName: pkg.packageName,
  }));

  return (
    <ResponsiveContainer width="100%" height={350}>
      <Treemap
        data={data}
        dataKey="size"
        nameKey="name"
        content={<CustomNode x={0} y={0} width={0} height={0} name="" fill="" />}
        onClick={(node: any) => {
          if (node?.packageName) onSelectPackage(node.packageName);
        }}
      >
        <Tooltip
          contentStyle={{
            backgroundColor: "#1e293b",
            border: "1px solid #334155",
            borderRadius: "6px",
            fontSize: "12px",
          }}
          formatter={(_: any, __: any, props: any) => [
            `${props.payload.size} classes`,
            props.payload.name,
          ]}
        />
      </Treemap>
    </ResponsiveContainer>
  );
}
