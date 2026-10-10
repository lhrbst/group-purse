import { startStack } from './stack';

export default async function globalSetup() {
  const stop = await startStack();
  // Playwright ruft die zurückgegebene Funktion nach dem letzten Test auf
  return stop;
}
