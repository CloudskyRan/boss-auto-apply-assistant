"use client";

import "./globals.css";
import Sidebar from "./components/Sidebar";
import ContentArea from "./components/ContentArea";
import { ThemeProvider } from "next-themes";
import { APP_NAME } from "@/lib/brand";

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="zh-CN" suppressHydrationWarning>
      <head>
        <title>{`${APP_NAME} Mitu · 个人求职工作台`}</title>
        <meta name="description" content="觅途，按自己的节奏发现岗位、管理投递、走向下一站。" />
      </head>
      <body suppressHydrationWarning>
        <a href="#main-content" className="sr-only z-[100] rounded-lg bg-card px-4 py-2 focus:not-sr-only focus:fixed focus:left-4 focus:top-4">跳转到主要内容</a>
        <ThemeProvider
          attribute="class"
          defaultTheme="light"
          enableSystem={false}
        >
          <div className="min-h-screen">
            <Sidebar />
            <ContentArea>
              {children}
            </ContentArea>
          </div>
        </ThemeProvider>
      </body>
    </html>
  );
}
