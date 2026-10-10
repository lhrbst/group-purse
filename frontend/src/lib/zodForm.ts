import type { ZodType } from 'zod';

/** Verbindet ein zod-Schema mit der validate-Option von Mantine Form. */
export function zodValidate<T>(schema: ZodType<T>) {
  return (values: unknown): Record<string, string> => {
    const result = schema.safeParse(values);
    if (result.success) return {};
    const errors: Record<string, string> = {};
    for (const issue of result.error.issues) {
      const key = issue.path.join('.');
      if (!(key in errors)) errors[key] = issue.message;
    }
    return errors;
  };
}
