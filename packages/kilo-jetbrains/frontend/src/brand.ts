export const BRAND = {
  id: "costrict",
  name: "Costrict",
  displayName: "Costrict",
  icon: "costrict.icon.svg",
  statusIcon: {
    connected: "costrict.status.connected.svg",
    disconnected: "costrict.status.disconnected.svg",
    reconnecting: "costrict.status.reconnecting.svg",
    unavailable: "costrict.status.unavailable.svg",
  },
} as const;

/** 将动态字符串中残留的旧品牌替换为 Costrict */
export function applyBranding(text: string): string {
  return text.replace(/Kilo\s*Code/g, BRAND.name).replace(/(?<![A-Za-z])Kilo(?![A-Za-z])/g, BRAND.name);
}
