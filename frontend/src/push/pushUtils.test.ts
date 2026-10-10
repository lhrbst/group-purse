import { describe, expect, it } from 'vitest';
import { bytesToBase64Url, isPushSupported, subscriptionToRequest, urlBase64ToUint8Array } from './pushUtils';

describe('push utils', () => {
  it('is unsupported without PushManager', () => {
    expect(isPushSupported({ serviceWorker: {}, Notification: {} })).toBe(false);
    expect(isPushSupported({ serviceWorker: {}, PushManager: {}, Notification: {} })).toBe(true);
  });

  it('round-trips base64url keys', () => {
    const bytes = new Uint8Array([251, 255, 0, 1, 2, 250]);
    const encoded = bytesToBase64Url(bytes.buffer);
    expect(encoded).not.toMatch(/[+/=]/);
    expect(Array.from(urlBase64ToUint8Array(encoded))).toEqual(Array.from(bytes));
  });

  it('converts a subscription into the API request', () => {
    const key = (n: number[]) => new Uint8Array(n).buffer;
    const req = subscriptionToRequest({
      endpoint: 'https://push.example/abc',
      getKey: (name) => (name === 'p256dh' ? key([1, 2, 3]) : key([4, 5])),
    });
    expect(req).toEqual({ endpoint: 'https://push.example/abc', p256dh: bytesToBase64Url(key([1, 2, 3])), auth: bytesToBase64Url(key([4, 5])) });
  });
});
