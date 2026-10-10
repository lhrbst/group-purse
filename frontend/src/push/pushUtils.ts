/** Der öffentliche VAPID-Schlüssel kommt base64url-kodiert vom Server und muss als Bytefolge an den Browser. */
export function urlBase64ToUint8Array(base64: string): Uint8Array<ArrayBuffer> {
  const padding = '='.repeat((4 - (base64.length % 4)) % 4);
  const raw = atob((base64 + padding).replace(/-/g, '+').replace(/_/g, '/'));
  const out = new Uint8Array(new ArrayBuffer(raw.length));
  for (let i = 0; i < raw.length; i++) out[i] = raw.charCodeAt(i);
  return out;
}

export function bytesToBase64Url(buffer: ArrayBuffer | null): string {
  if (!buffer) return '';
  let s = '';
  new Uint8Array(buffer).forEach((b) => (s += String.fromCharCode(b)));
  return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

export function isPushSupported(env: { serviceWorker?: unknown; PushManager?: unknown; Notification?: unknown } = {
  serviceWorker: typeof navigator !== 'undefined' ? (navigator as { serviceWorker?: unknown }).serviceWorker : undefined,
  PushManager: typeof window !== 'undefined' ? (window as { PushManager?: unknown }).PushManager : undefined,
  Notification: typeof window !== 'undefined' ? (window as { Notification?: unknown }).Notification : undefined,
}): boolean {
  return !!env.serviceWorker && !!env.PushManager && !!env.Notification;
}

/** Wandelt eine Browser-Subscription in die Anfrage an unsere API um. */
export function subscriptionToRequest(sub: { endpoint: string; getKey(name: 'p256dh' | 'auth'): ArrayBuffer | null }) {
  return { endpoint: sub.endpoint, p256dh: bytesToBase64Url(sub.getKey('p256dh')), auth: bytesToBase64Url(sub.getKey('auth')) };
}
