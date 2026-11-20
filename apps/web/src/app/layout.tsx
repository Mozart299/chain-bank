import type { Metadata } from "next";
import { Geist } from "next/font/google";

import { SessionProvider } from "@/components/session-provider";
import { SiteHeader } from "@/components/site-header";
import { ThemeProvider } from "@/components/theme-provider";
import { Toaster } from "@/components/ui/sonner";
import { cn } from "@/lib/utils";

import "./globals.css";

const geist = Geist({ subsets: ["latin"], variable: "--font-sans" });

export const metadata: Metadata = {
  title: "ChainBank",
  description: "Banking simulator with blockchain settlement. All balances are fictional.",
};

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    // next-themes sets the .dark class before hydration; suppress the expected attribute mismatch.
    <html lang="en" className={cn("h-full antialiased font-sans", geist.variable)} suppressHydrationWarning>
      <body className="flex min-h-full flex-col">
        <ThemeProvider attribute="class" defaultTheme="system" enableSystem disableTransitionOnChange>
          <SessionProvider>
            <SiteHeader />
            <div className="flex flex-1 flex-col">{children}</div>
          </SessionProvider>
          <Toaster richColors />
        </ThemeProvider>
      </body>
    </html>
  );
}
