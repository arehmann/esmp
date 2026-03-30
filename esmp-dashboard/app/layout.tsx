import type { Metadata } from "next";
import { Geist } from "next/font/google";
import "./globals.css";
import { AppShell } from "@/components/app-shell";

const geistSans = Geist({
  subsets: ["latin"],
  variable: "--font-geist-sans",
});

export const metadata: Metadata = {
  title: "ESMP Dashboard",
  description: "Migration intelligence for adsuite-market",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en" className={`dark ${geistSans.variable}`}>
      <body className={geistSans.className}>
        <AppShell>{children}</AppShell>
      </body>
    </html>
  );
}
