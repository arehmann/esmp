import { Badge } from "@/components/ui/badge";

export function StereotypeBadges({ labels }: { labels: string[] }) {
  return (
    <div className="flex flex-wrap gap-1">
      {labels.map((label) => (
        <Badge key={label} variant="secondary" className="text-[10px]">
          {label.replace("Java", "").replace("Vaadin", "V:")}
        </Badge>
      ))}
    </div>
  );
}
