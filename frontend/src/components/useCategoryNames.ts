import { useCategoryList } from '../api/generated/category-controller/category-controller';

/** Kategorie-ID -> Name, inklusive ausgeblendeter (für die Anzeige alter Buchungen). Nur für Kassenverwalter. */
export function useCategoryNames(): Map<string, string> {
  const q = useCategoryList({ alle: true });
  const map = new Map<string, string>();
  if (q.data?.status === 200) q.data.data.forEach((c) => map.set(c.id!, c.name ?? ''));
  return map;
}
