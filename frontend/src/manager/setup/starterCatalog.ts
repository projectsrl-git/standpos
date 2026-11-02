import type { TFunction } from 'i18next';
import { api } from '../../api/client';
import type { Category, RouteItem } from '../../api/types';

/** The example catalogue: names are i18n keys under setup.starter, so it is created in the UI language. */
export const STARTER_ROUTES = [
  { key: 'bar', sort: 0 },
  { key: 'kitchen', sort: 1 },
] as const;

type RouteKey = (typeof STARTER_ROUTES)[number]['key'];

export const STARTER_CATEGORIES: {
  key: string;
  colour: string;
  route: RouteKey;
  products: { key: string; priceCents: number }[];
}[] = [
  {
    key: 'drinks',
    colour: '#1E88E5',
    route: 'bar',
    products: [
      { key: 'water', priceCents: 100 },
      { key: 'softDrink', priceCents: 250 },
      { key: 'coffee', priceCents: 100 },
    ],
  },
  {
    key: 'food',
    colour: '#E53935',
    route: 'kitchen',
    products: [
      { key: 'sandwich', priceCents: 500 },
      { key: 'fries', priceCents: 300 },
      { key: 'toast', priceCents: 300 },
    ],
  },
  {
    key: 'sweets',
    colour: '#8E24AA',
    route: 'bar',
    products: [
      { key: 'cake', priceCents: 250 },
      { key: 'iceCream', priceCents: 200 },
    ],
  },
];

/**
 * Creates the Bar and Cucina routes (reusing routes of the same name), then the example categories and products,
 * through the normal catalogue API. Stock tracking is off: the owner turns it on per product.
 */
export async function createStarterCatalog(venueId: string, t: TFunction): Promise<void> {
  const base = `/venues/${venueId}`;
  const existing = await api.get<RouteItem[]>(`${base}/routes`);
  const routeIds = {} as Record<RouteKey, string>;
  for (const route of STARTER_ROUTES) {
    const name = t(`setup.starter.routes.${route.key}`);
    const found = existing.find((r) => r.name.toLowerCase() === name.toLowerCase());
    routeIds[route.key] = found
      ? found.id
      : (await api.post<RouteItem>(`${base}/routes`, { name, sort: route.sort })).id;
  }
  for (const [categorySort, category] of STARTER_CATEGORIES.entries()) {
    const created = await api.post<Category>(`${base}/categories`, {
      name: t(`setup.starter.categories.${category.key}`),
      colour: category.colour,
      sort: categorySort,
      defaultRouteId: routeIds[category.route],
    });
    for (const [productSort, product] of category.products.entries()) {
      await api.post(`${base}/products`, {
        categoryId: created.id,
        name: t(`setup.starter.products.${product.key}`),
        priceCents: product.priceCents,
        routeId: null,
        active: true,
        trackStock: false,
        lowThreshold: null,
        soldOut: false,
        sort: productSort,
      });
    }
  }
}
