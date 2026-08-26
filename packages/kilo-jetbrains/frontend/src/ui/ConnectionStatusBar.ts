import { BRAND } from "../brand.ts";
import type { Diagnostic, FailureSignal } from "../connection/diagnostics.ts";
import { diagnoseFailure } from "../connection/diagnostics.ts";
import type { ConnectionStatus } from "../connection/status.ts";
import { ConnectionStatusStore } from "../connection/status.ts";
import { zhCN } from "../i18n/zh-CN.ts";

export interface StatusBarView {
  icon: string;
  text: string;
  tooltip: string;
}

export type StatusBarRenderer = (view: StatusBarView) => void;

export class ConnectionStatusBar {
  private store = new ConnectionStatusStore();
  private diagnostic: Diagnostic | null = null;
  private render: StatusBarRenderer;

  constructor(render: StatusBarRenderer) {
    this.render = render;
    this.store.subscribe(() => this.update());
    this.update();
  }

  setStatus(status: ConnectionStatus): void {
    if (status === "connected") this.diagnostic = null;
    this.store.set(status);
  }

  reportFailure(signal: FailureSignal): Diagnostic {
    this.diagnostic = diagnoseFailure(signal);
    this.update();
    return this.diagnostic;
  }

  getStatus(): ConnectionStatus {
    return this.store.get();
  }

  currentView(): StatusBarView {
    const status = this.store.get();
    const icon = BRAND.statusIcon[status];
    const text = zhCN.connection[status];
    const tooltip = this.diagnostic
      ? `${zhCN.tooltip[status]}\n${this.diagnostic.title}: ${this.diagnostic.message}\n${this.diagnostic.hint}`
      : zhCN.tooltip[status];
    return { icon, text, tooltip };
  }

  private update(): void {
    this.render(this.currentView());
  }
}
