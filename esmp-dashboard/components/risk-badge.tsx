import { Badge } from "@/components/ui/badge";
import { riskLabel, cn } from "@/lib/utils";

export function RiskBadge({ score }: { score: number }) {
  const label = riskLabel(score);
  return (
    <Badge variant="outline"
      className={cn("text-xs",
        label === "LOW" && "border-risk-low text-risk-low",
        label === "MEDIUM" && "border-risk-medium text-risk-medium",
        label === "HIGH" && "border-risk-high text-risk-high"
      )}
    >
      {score.toFixed(2)}
    </Badge>
  );
}
