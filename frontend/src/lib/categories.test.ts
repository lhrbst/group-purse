import { describe, expect, it } from 'vitest';
import { categoryLabel, describePositions, leafCategories, leafOptions } from './categories';

const cats = [
  { id: 'food', name: 'Lebensmittel' },
  { id: 'obst', name: 'Obst und Gemüse', parentId: 'food' },
  { id: 'milch', name: 'Milch, Käse und Eier', parentId: 'food' },
  { id: 'haushalt', name: 'Haushalt' },
];

describe('categories', () => {
  it('offers only leaves for positions', () => {
    expect(leafCategories(cats).map((c) => c.id)).toEqual(['obst', 'milch', 'haushalt']);
  });

  it('labels subcategories with their parent', () => {
    expect(categoryLabel(cats, 'obst')).toBe('Lebensmittel › Obst und Gemüse');
    expect(categoryLabel(cats, 'haushalt')).toBe('Haushalt');
    expect(categoryLabel(cats, 'unbekannt')).toBe('Kategorie');
  });

  it('builds select options from leaves', () => {
    expect(leafOptions(cats)[0]).toEqual({ value: 'obst', label: 'Lebensmittel › Obst und Gemüse' });
  });

  it('a parent whose children are all hidden becomes selectable again', () => {
    expect(leafCategories([{ id: 'food', name: 'Lebensmittel' }]).map((c) => c.id)).toEqual(['food']);
  });

  it('describes positions with amounts', () => {
    const text = describePositions([{ kategorieId: 'obst', betrag: 8.4 }, { kategorieId: 'haushalt', betrag: 4.99 }], (id) => cats.find((c) => c.id === id)?.name ?? '?').replace(/ /g, ' ');
    expect(text).toBe('Obst und Gemüse 8,40 € · Haushalt 4,99 €');
    expect(describePositions(undefined, () => '')).toBe('');
  });
});
