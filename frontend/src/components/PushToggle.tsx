import { useEffect, useState } from 'react';
import { Alert, Button, Stack, Text } from '@mantine/core';
import { pushPublicKey, pushSubscribe, pushUnsubscribe } from '../api/generated/push-controller/push-controller';
import { notifyError, notifySuccess } from '../lib/errors';
import { isPushSupported, subscriptionToRequest, urlBase64ToUint8Array } from '../push/pushUtils';

/** Schaltet Web-Push für dieses Gerät und die aktive Wohngruppe ein oder aus. */
export function PushToggle() {
  const supported = isPushSupported();
  const [subscribed, setSubscribed] = useState(false);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (!supported) return;
    navigator.serviceWorker.ready
      .then((reg) => reg.pushManager.getSubscription())
      .then((sub) => setSubscribed(!!sub))
      .catch(() => undefined);
  }, [supported]);

  if (!supported) {
    return <Text size="sm" c="dimmed">Dieses Gerät oder dieser Browser unterstützt keine Push-Benachrichtigungen. Die Warnungen kommen weiterhin per E-Mail.</Text>;
  }

  async function enable() {
    setBusy(true);
    try {
      const keyRes = await pushPublicKey();
      const key = keyRes.status === 200 ? keyRes.data.publicKey : '';
      if (!key) throw new Error('Push ist auf dem Server nicht eingerichtet.');
      const permission = await Notification.requestPermission();
      if (permission !== 'granted') throw new Error('Benachrichtigungen wurden nicht erlaubt.');
      const reg = await navigator.serviceWorker.ready;
      const sub = (await reg.pushManager.getSubscription()) ?? (await reg.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: urlBase64ToUint8Array(key) }));
      await pushSubscribe(subscriptionToRequest(sub));
      setSubscribed(true);
      notifySuccess('Benachrichtigungen sind aktiv.');
    } catch (e) {
      notifyError(e);
    } finally {
      setBusy(false);
    }
  }

  async function disable() {
    setBusy(true);
    try {
      const reg = await navigator.serviceWorker.ready;
      const sub = await reg.pushManager.getSubscription();
      if (sub) {
        await pushUnsubscribe({ endpoint: sub.endpoint });
        await sub.unsubscribe();
      }
      setSubscribed(false);
    } catch (e) {
      notifyError(e);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Stack gap="xs">
      <Text size="sm" c="dimmed">
        Wenn die Barkasse unter den Mindestbetrag fällt, bekommst du eine Nachricht auf dieses Gerät (zusätzlich zur E-Mail). Gilt für die gerade gewählte Wohngruppe.
      </Text>
      {subscribed ? (
        <Button variant="light" color="gray" loading={busy} onClick={disable}>Benachrichtigungen ausschalten</Button>
      ) : (
        <Button loading={busy} onClick={enable}>Benachrichtigungen einschalten</Button>
      )}
      <Alert variant="light" color="blue">Auf dem iPhone funktioniert Push nur, wenn die App zum Home-Bildschirm hinzugefügt wurde.</Alert>
    </Stack>
  );
}
