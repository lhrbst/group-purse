import { useCategoryList } from '../api/generated/category-controller/category-controller';
import { categoryLabel } from '../lib/categories';

/** Kategorie-ID -> Anzeigename mit Oberkategorie ("Lebensmittel › Getränke"), inklusive ausgeblendeter. Nur für Kassenverwalter. */
export function useCategoryNames(): Map<string, string> {
  const q = useCategoryList({ alle: true });
  const list = q.data?.status === 200 ? q.data.data : [];
  return new Map(list.map((c) => [c.id!, categoryLabel(list, c.id)]));
}
