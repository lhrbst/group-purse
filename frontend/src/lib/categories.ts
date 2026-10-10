import type { PositionDto } from '../api/model';
import { formatEuro } from './format';

export interface CategoryLike {
  id?: string;
  name?: string;
  parentId?: string | null;
}

/** Kategorien, die keine Unterkategorie haben: nur sie sind auf einer Belegposition wählbar. */
export function leafCategories<T extends CategoryLike>(cats: T[]): T[] {
  const parents = new Set(cats.map((c) => c.parentId).filter((p): p is string => !!p));
  return cats.filter((c) => !c.id || !parents.has(c.id));
}

/** "Lebensmittel › Obst und Gemüse" bzw. nur der Name bei Hauptkategorien. */
export function categoryLabel(cats: CategoryLike[], id?: string): string {
  const c = cats.find((x) => x.id === id);
  if (!c) return 'Kategorie';
  const parent = c.parentId ? cats.find((x) => x.id === c.parentId) : undefined;
  return parent ? `${parent.name} › ${c.name}` : (c.name ?? '');
}

export function leafOptions(cats: CategoryLike[]): { value: string; label: string }[] {
  return leafCategories(cats).map((c) => ({ value: c.id!, label: categoryLabel(cats, c.id) }));
}

/** "Obst und Gemüse 8,40 € · Haushalt 4,99 €" */
export function describePositions(positions: PositionDto[] | undefined, nameOf: (id?: string) => string): string {
  return (positions ?? []).map((p) => `${nameOf(p.kategorieId)} ${formatEuro(p.betrag)}`).join(' · ');
}
