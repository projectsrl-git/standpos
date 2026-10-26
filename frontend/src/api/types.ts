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
  allowNegativeStock: boolean;
}

export interface User {
  id: string;
  displayName: string;
  email: string | null;
  hasPassword: boolean;
  hasPin: boolean;
  lockedUntil: string | null;
  pinLockedUntil: string | null;
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
  /** For a register: the printer its receipts go to. */
  printerId: string | null;
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
  /** Orders or stock reference it: it can be deactivated, not deleted. */
  inUse: boolean;
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
    products: GridProduct[];
  }[];
}

// Phase 1: stock, sales, printing, usage.

export type MovementType =
  'RECEIPT' | 'SALE' | 'SALE_REVERSAL' | 'RETURN_TO_STOCK' | 'WASTE' | 'ADJUSTMENT' | 'COUNT';

export interface StockLevel {
  productId: string;
  productName: string;
  categoryId: string;
  active: boolean;
  onHand: number;
  valueCents: number;
  averageCostCents: number;
  lowThreshold: number | null;
  low: boolean;
  soldOut: boolean;
  manualSoldOut: boolean;
}

export interface StockMovement {
  id: string;
  productId: string;
  type: MovementType;
  quantity: number;
  valueCents: number;
  onHandAfter: number;
  referenceType: string | null;
  referenceId: string | null;
  userId: string | null;
  note: string | null;
  createdAt: string;
}

export interface Supplier {
  id: string;
  name: string;
  contactName: string | null;
  phone: string | null;
  email: string | null;
  notes: string | null;
  active: boolean;
}

export interface GoodsReceipt {
  id: string;
  supplierId: string | null;
  documentRef: string | null;
  receivedOn: string;
  notes: string | null;
  status: 'DRAFT' | 'POSTED';
  postedAt: string | null;
  totalCostCents: number;
  lines: { productId: string; quantity: number; unitCostCents: number | null }[];
}

export interface StockCount {
  id: string;
  status: 'DRAFT' | 'POSTED';
  notes: string | null;
  createdAt: string;
  postedAt: string | null;
  lines: { productId: string; expected: number; counted: number | null; difference: number | null }[];
}

export interface Printer {
  id: string;
  agentId: string;
  agentName: string | null;
  name: string;
  windowsName: string;
  widthMm: number;
  codePage: string;
  active: boolean;
  online: boolean;
  lastHeartbeat: string | null;
  localPrinters: string[];
}

export interface VenueUserLock {
  userId: string;
  displayName: string;
  roles: Role[];
  lockedUntil: string | null;
  pinLockedUntil: string | null;
}

export interface Usage {
  month: string;
  sales: number;
  cap: number;
  warning: boolean;
  capReached: boolean;
  capReachedAt: string | null;
  blockedFrom: string | null;
  blocked: boolean;
}

export type PaymentMethod = 'CASH' | 'MANUAL_CARD' | 'SUMUP_CARD';
export type ReceiptState = 'NOT_REQUESTED' | 'NO_PRINTER' | 'QUEUED' | 'SENT' | 'PRINTED' | 'FAILED';
export type VoidDisposition = 'RETURN_TO_STOCK' | 'WASTE';

export interface OrderLine {
  id: string;
  lineNo: number;
  productId: string;
  name: string;
  unitPriceCents: number;
  quantity: number;
  lineTotalCents: number;
  note: string | null;
  voidDisposition: VoidDisposition | null;
}

export interface Order {
  id: string;
  clientOrderId: string;
  dailyNumber: number;
  businessDate: string;
  status: 'AWAITING_PAYMENT' | 'PAID' | 'CANCELLED' | 'VOIDED';
  totalCents: number;
  createdAt: string;
  paidAt: string | null;
  deviceId: string;
  receiptPrinted: boolean;
  receipt: ReceiptState;
  receiptError: string | null;
  lines: OrderLine[];
  payment: {
    method: PaymentMethod;
    status: 'PENDING' | 'CONFIRMED' | 'FAILED' | 'REFUNDED';
    amountCents: number;
    tenderedCents: number | null;
    changeCents: number | null;
    needsReconciliation: boolean;
  } | null;
  voidedAt: string | null;
  voidReason: string | null;
}

export interface Shift {
  id: string;
  cashierId: string;
  openedAt: string;
  openingFloatCents: number;
  closedAt: string | null;
  paidOrders: number;
  voidedOrders: number;
  cashSalesCents: number;
  manualCardCents: number;
  expectedCashCents: number;
  countedCashCents: number | null;
  differenceCents: number | null;
}

export interface RegisterStatus {
  shift: Shift | null;
  printer: { id: string; name: string; agentId: string; online: boolean } | null;
  usage: { capReached: boolean; blockedFrom: string | null; blocked: boolean };
}

/** Pushed on /topic/venues/{venueId}/stock. */
export interface StockEvent {
  venueId: string;
  productId: string;
  onHand: number | null;
  soldOut: boolean;
  low: boolean;
}

/** Pushed on /topic/venues/{venueId}/printers. */
export interface AgentStatusEvent {
  venueId: string;
  agentId: string;
  online: boolean;
}

/** Pushed on /topic/venues/{venueId}/print-jobs. */
export interface JobFinishedEvent {
  venueId: string;
  jobId: string;
  orderId: string | null;
  kind: string;
  status: 'PRINTED' | 'FAILED';
  error: string | null;
}

export interface GridProduct {
  id: string;
  name: string;
  priceCents: number;
  soldOut: boolean;
  /** Null for products without stock tracking. */
  onHand: number | null;
  /** At or below the low threshold: the register shows what is left. */
  low: boolean;
}
