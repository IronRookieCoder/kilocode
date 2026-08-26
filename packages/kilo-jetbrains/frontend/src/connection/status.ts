export const CONNECTION_STATUSES = [
  "connected",
  "disconnected",
  "reconnecting",
  "unavailable",
] as const;

export type ConnectionStatus = (typeof CONNECTION_STATUSES)[number];

export type ConnectionStatusListener = (status: ConnectionStatus) => void;

const VALID = new Set<string>(CONNECTION_STATUSES);

export class ConnectionStatusStore {
  private status: ConnectionStatus = "disconnected";
  private listeners = new Set<ConnectionStatusListener>();

  get(): ConnectionStatus {
    return this.status;
  }

  set(next: ConnectionStatus): void {
    if (!VALID.has(next)) {
      throw new Error(`无效的连接状态: ${String(next)}`);
    }
    if (next === this.status) return;
    this.status = next;
    for (const listener of this.listeners) listener(next);
  }

  subscribe(listener: ConnectionStatusListener): () => void {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  }
}
