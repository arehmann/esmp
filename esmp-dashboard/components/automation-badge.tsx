import { Badge } from "@/components/ui/badge";
import { automationLabel, cn } from "@/lib/utils";

export function AutomationBadge({ automatable }: { automatable: string }) {
  const label = automationLabel(automatable);
  return (
    <Badge variant="outline"
      className={cn("text-xs",
        label === "Auto" && "border-auto-full text-auto-full",
        label === "AI-Assisted" && "border-auto-ai text-auto-ai",
        label === "Manual" && "border-auto-manual text-auto-manual"
      )}
    >
      {label}
    </Badge>
  );
}
