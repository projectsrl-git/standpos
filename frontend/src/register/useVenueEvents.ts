import { Client } from '@stomp/stompjs';
import { useEffect, useRef, useState } from 'react';
import type { AgentStatusEvent, JobFinishedEvent, StockEvent } from '../api/types';

export type Connection = 'connecting' | 'online' | 'offline';

export interface VenueEventHandlers {
  onStock?: (event: StockEvent) => void;
  onPrinter?: (event: AgentStatusEvent) => void;
  onPrintJob?: (event: JobFinishedEvent) => void;
  onReconnect?: () => void;
}

/** The WebSocket URL on the page's own origin (Caddy in production, the Vite proxy in development). */
export function webSocketUrl(location: Location = window.location): string {
  const scheme = location.protocol === 'https:' ? 'wss' : 'ws';
  return `${scheme}://${location.host}/ws`;
}

/**
 * Live events of a venue over STOMP: stock and sold-out changes, printer online/offline, finished print jobs. The
 * session cookie authenticates the WebSocket handshake; the client reconnects by itself, and `onReconnect` lets the
 * page reload what it may have missed meanwhile. Returns the connection state:
 * "connecting" until the first connection, then "online" or "offline".
 */
export function useVenueEvents(venueId: string | null | undefined, handlers: VenueEventHandlers): Connection {
  const [connection, setConnection] = useState<Connection>('connecting');
  const current = useRef(handlers);

  useEffect(() => {
    current.current = handlers;
  });

  useEffect(() => {
    if (!venueId) return undefined;
    const client = new Client({
      brokerURL: webSocketUrl(),
      reconnectDelay: 3000,
      heartbeatIncoming: 10000,
      heartbeatOutgoing: 10000,
    });
    const topic = `/topic/venues/${venueId}`;
    let connections = 0;
    client.onConnect = () => {
      setConnection('online');
      connections += 1;
      if (connections > 1) current.current.onReconnect?.();
      client.subscribe(`${topic}/stock`, (m) => current.current.onStock?.(JSON.parse(m.body) as StockEvent));
      client.subscribe(`${topic}/printers`, (m) =>
        current.current.onPrinter?.(JSON.parse(m.body) as AgentStatusEvent),
      );
      client.subscribe(`${topic}/print-jobs`, (m) =>
        current.current.onPrintJob?.(JSON.parse(m.body) as JobFinishedEvent),
      );
    };
    client.onWebSocketClose = () => setConnection('offline');
    client.activate();
    return () => {
      void client.deactivate();
    };
  }, [venueId]);

  return connection;
}
