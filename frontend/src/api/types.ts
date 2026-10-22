export type Role = 'PLATFORM_ADMIN' | 'OWNER' | 'MANAGER' | 'STOCK_CLERK' | 'CASHIER' | 'KITCHEN';
export type DeviceKind = 'REGISTER' | 'KITCHEN' | 'PRINT_AGENT';
export type PrincipalKind = 'USER' | 'CASHIER' | 'DEVICE';

export interface MembershipInfo {
  id: string;
  venueId: string | null;
  role: Role;
}

export interface Me {
  kind: PrincipalKind;
  tenant: { id: string; name: string } | null;
  user: { id: string; displayName: string; email: string | null } | null;
  memberships: MembershipInfo[];
  device: { id: string; venueId: string; kind: DeviceKind; name: string } | null;
  platformAdmin: boolean;
}

export interface CurrentDevice {
  enrolled: boolean;
  id: string | null;
  venueId: string | null;
  kind: DeviceKind | null;
  name: string | null;
}

export interface Venue {
  id: string;
  name: string;
  kind: 'PERMANENT' | 'EVENT';
  timeZone: string;
  dayChangeTime: string;
  receiptHeader: string | null;
  receiptFooter: string | null;
  ticketMode: 'RECEIPT_ONLY' | 'RECEIPT_AND_TICKETS';
  active: boolean;
}

export interface User {
  id: string;
  displayName: string;
  email: string | null;
  hasPassword: boolean;
  hasPin: boolean;
  memberships: MembershipInfo[];
}

export interface Device {
  id: string;
  venueId: string;
  kind: DeviceKind;
  name: string;
  lastSeenAt: string | null;
  revokedAt: string | null;
  createdAt: string;
}

export interface EnrolmentCode {
  code: string;
  kind: DeviceKind;
  expiresAt: string;
}

export interface RouteItem {
  id: string;
  name: string;
  sort: number;
}

export interface Category {
  id: string;
  name: string;
  colour: string;
  sort: number;
  defaultRouteId: string | null;
}

export interface Product {
  id: string;
  categoryId: string;
  name: string;
  priceCents: number;
  routeId: string | null;
  active: boolean;
  trackStock: boolean;
  lowThreshold: number | null;
  soldOut: boolean;
  sort: number;
}

export interface Cashier {
  userId: string;
  displayName: string;
}

export interface RegisterGrid {
  venueId: string;
  venueName: string;
  categories: {
    id: string;
    name: string;
    colour: string;
    products: { id: string; name: string; priceCents: number; soldOut: boolean }[];
  }[];
}
